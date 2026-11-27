package rotp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rotp.model.game.IGameOptions;
import rotp.model.game.RulesetManager;
import rotp.multiplayer.session.MatchOutcome.Cause;
import static org.junit.jupiter.api.Assertions.*;

class HeadlessMatchTest {
    @TempDir Path directory;

    private String run(int expectedStatus, String entry, String... args) throws Exception {
        Path output = directory.resolve("output.txt");
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx2g", "-Djava.awt.headless=true", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), entry));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove("DISPLAY");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Headless match timed out");
            var text = Files.readString(output);
            assertEquals(expectedStatus, process.exitValue(), text);
            return text;
        } finally {
            process.destroyForcibly();
        }
    }

    @Test void cliPlaysToVictory() throws Exception {
        String result = run(0, "rotp.Launcher", "--headless");
        assertTrue(result.contains("Winner "), result);
    }

    @Test void validatesArguments() throws Exception {
        assertTrue(run(0, "rotp.Launcher$Mini", "--headless", "--help").contains("--ais"));
        assertTrue(run(2, "rotp.Launcher", "--headless", "--ais", "missing").contains("Unknown AI"));
        assertTrue(run(2, "rotp.Launcher", "--headless", "--opponents", "1000").contains("Too many opponents"));
    }

    @Test void aiRosterSurvivesLossOfFirstEmpire() throws Exception {
        run(0, getClass().getName());
    }

    public static void main(String[] args) {
        Launcher.enableHeadless();
        rotp.model.planet.PlanetFactory.current();
        rotp.model.tech.TechLibrary.current();
        rotp.util.LanguageManager.current().selectedLanguageName();
        var options = RulesetManager.current().newOptions();
        var session = HeadlessMatch.start(options, 2, new String[]{"base", "modnar", "fusion"});
        var galaxy = session.galaxy();
        assertEquals(IGameOptions.BASE, options.selectedAI(galaxy.empire(0)));
        assertEquals(IGameOptions.MODNAR, options.selectedAI(galaxy.empire(1)));
        assertEquals(IGameOptions.FUSION, options.selectedAI(galaxy.empire(2)));
        for (var system : List.copyOf(galaxy.empire(0).allColonizedSystems()))
            system.colony().destroy();
        assertEquals(Cause.ONGOING, session.matchOutcome().cause());
        session.advanceInProcessTurn();
        var first = galaxy.empire(1);
        var second = galaxy.empire(2);
        first.viewForEmpire(second).embassy().contact(true);
        second.viewForEmpire(first).embassy().contact(true);
        first.viewForEmpire(second).embassy().signAlliance();
        assertEquals(Cause.MILITARY_ALLIANCE, session.matchOutcome().cause());
        for (var system : List.copyOf(second.allColonizedSystems()))
            system.colony().destroy();
        assertEquals(Cause.MILITARY, session.matchOutcome().cause());
        System.exit(0);
    }
}
