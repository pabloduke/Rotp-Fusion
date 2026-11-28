package rotp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rotp.replay.ReplayArchive;
import rotp.replay.ReplayRecorder;
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
        try (var files = Files.list(directory.resolve("replays"))) {
            var names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(2, names.size());
            assertTrue(names.get(0).endsWith(".mp4"));
            assertEquals(names.get(0).replace(".mp4", ".rotpreplay"), names.get(1));
        }
    }

    @Test void recordsEveryTurnThroughThePrintedVictory() throws Exception {
        Path path = directory.resolve("replays/MixedCase.rotpreplay");
        String output = run(0, "rotp.Launcher", "--headless", "--record", "MixedCase.rotpreplay");
        assertTrue(Files.size(path.resolveSibling("MixedCase.mp4")) > 0);
        String verified = run(0, getClass().getName(), "verify", path.toString());
        assertTrue(output.contains(verified.lines().filter(line -> line.startsWith("Turn ")).findFirst().orElseThrow()));
    }

    private static void verifyArchive(Path path) throws Exception {
        try (var archive = new ReplayArchive(path); var zip = new java.util.zip.ZipFile(path.toFile())) {
            var originalSession = rotp.model.game.GameSession.instance();
            int previousTurn = 0;
            int events = 0;
            for (int i = 0; i < archive.size(); i++) {
                var frame = archive.frame(i);
                assertTrue(frame.turn() == previousTurn + 1 ||
                        (frame.turn() == previousTurn && i == archive.size()-1));
                previousTurn = frame.turn();
                events += frame.events().size();
                // Read only snapshots produced by this test, independently of the playback reader.
                try (var in = new java.io.ObjectInputStream(zip.getInputStream(zip.getEntry("state/" + i)))) {
                    var saved = (rotp.model.game.GameSession) in.readObject();
                    assertEquals(saved.galaxy().currentTurn(), frame.turn());
                    var systems = frame.items().stream().filter(it -> it.kind().equals("System")).toList();
                    assertEquals(saved.galaxy().numStarSystems(), systems.size());
                    for (int j = 0; j < systems.size(); j++) {
                        var system = saved.galaxy().system(j);
                        var item = systems.get(j);
                        assertEquals(system.empId(), item.empire());
                        assertEquals(system.x(), item.x());
                        assertEquals(system.y(), item.y());
                        if (system.isColonized())
                            assertTrue(item.details().contains("Population: " + system.colony().population()));
                    }
                    for (var empireItem : frame.items().stream().filter(it -> it.kind().equals("Empire")).toList()) {
                        var empire = saved.galaxy().empire(empireItem.empire());
                        assertTrue(empireItem.details().startsWith(empire.extinct() ? "Eliminated" : "Active"));
                        for (int k = 0; k < rotp.model.tech.TechTree.NUM_CATEGORIES; k++) {
                            var cat = empire.tech().category(k);
                            assertTrue(empireItem.details().contains(cat.id() + ": " + cat.currentTech()));
                        }
                        var fleets = saved.galaxy().ships.allFleets(empire.id).stream().filter(f -> !f.isEmpty()).toList();
                        var shown = frame.items().stream().filter(it -> it.kind().equals("Fleet") && it.empire() == empire.id).toList();
                        assertEquals(fleets.size(), shown.size());
                        for (int j = 0; j < fleets.size(); j++) {
                            var fleet = fleets.get(j);
                            var displayed = shown.get(j);
                            float pct = fleet.inTransit() ? fleet.travelPct(saved.galaxy().currentTime()) : 0;
                            assertEquals(fleet.fromX() + pct*(fleet.destX()-fleet.fromX()), displayed.x(), 0.001f);
                            assertEquals(fleet.fromY() + pct*(fleet.destY()-fleet.fromY()), displayed.y(), 0.001f);
                            if (fleet.hasDestination())
                                assertTrue(displayed.details().contains("Destination system #" + fleet.destSysId()));
                        }
                    }
                }
            }
            assertTrue(events > 0);
            var last = archive.frame(archive.size()-1);
            assertFalse(last.outcome().isBlank());
            System.out.println("Turn " + last.turn() + " — " + last.outcome().split(" — ")[0]);
            assertEquals(1, archive.frame(0).turn());
            assertSame(originalSession, rotp.model.game.GameSession.instance());
        }
    }

    @Test void recordsTransientDiplomacyAndSameTurnFrames() throws Exception {
        run(0, getClass().getName(), directory.resolve("diplomacy.rotpreplay").toString());
    }

    @Test void rejectsIncompleteRecordingsAndMismatchedBuilds() throws Exception {
        Path path = directory.resolve("broken.rotpreplay");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("state/0"));
            zip.closeEntry();
        }
        var incomplete = assertThrows(java.io.IOException.class, () -> new ReplayArchive(path));
        assertTrue(incomplete.getMessage().contains("index"));
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("index"));
            var data = new java.io.DataOutputStream(zip);
            data.writeUTF("ROTP Replay 1");
            data.writeUTF("different build");
            zip.closeEntry();
        }
        var mismatch = assertThrows(java.io.IOException.class, () -> new ReplayArchive(path));
        assertTrue(mismatch.getMessage().contains("different build"));
    }

    @Test void validatesArguments() throws Exception {
        assertTrue(run(0, "rotp.Launcher$Mini", "--headless", "--help").contains("--ais"));
        assertTrue(run(2, "rotp.Launcher", "--headless", "--ais", "missing").contains("Unknown AI"));
        assertTrue(run(2, "rotp.Launcher", "--headless", "--opponents", "1000").contains("Too many opponents"));
    }

    @Test void scriptedLeadersDirectRealEmpires() throws Exception {
        run(0, getClass().getName(), "leaders");
    }

    @Test void eventDrivenLeadersRetainObjectivesAndBatchMail() throws Exception {
        run(0, getClass().getName(), "event");
    }

    @Test void objectiveCompletionAndFailedReviews() throws Exception {
        run(0, getClass().getName(), "objective");
    }

    @Test void aiRosterSurvivesLossOfFirstEmpire() throws Exception {
        run(0, getClass().getName());
    }

    public static void main(String[] args) throws Exception {
        Launcher.enableHeadless();
        rotp.model.planet.PlanetFactory.current();
        rotp.model.tech.TechLibrary.current();
        rotp.util.LanguageManager.current().selectedLanguageName();
        if (args.length == 2 && args[0].equals("verify")) {
            verifyArchive(Path.of(args[1]));
            System.exit(0);
        }
        var options = RulesetManager.current().newOptions();
        if (args.length == 1 && args[0].equals("objective")) {
            rotp.leader.ScriptedLeaders.objectiveLifecycle(Path.of("."), () -> HeadlessMatch.start(options, 2, new String[]{"fusion"}));
            System.exit(0);
        }
        if (args.length == 1 && args[0].equals("event")) {
            rotp.leader.ScriptedLeaders.eventDriven(Path.of("."), () -> HeadlessMatch.start(options, 2, new String[]{"fusion"}));
            System.exit(0);
        }
        if (args.length == 1 && args[0].equals("leaders")) {
            rotp.leader.ScriptedLeaders.run(Path.of("."), options,
                    () -> HeadlessMatch.start(options, 2, new String[]{"fusion"}));
            System.exit(0);
        }
        var session = HeadlessMatch.start(options, 2, new String[]{"base", "modnar", "fusion"});
        var galaxy = session.galaxy();
        if (args.length > 0) {
            var path = Path.of(args[0]);
            var first = galaxy.empire(0);
            var second = galaxy.empire(1);
            try (var recorder = new ReplayRecorder(path, session)) {
                recorder.capture();
                first.viewForEmpire(second).embassy().contact(true);
                second.viewForEmpire(first).embassy().contact(true);
                first.diplomatAI().refuseOfferTrade(second, 25);
                first.viewForEmpire(second).embassy().signPact();
                first.viewForEmpire(second).embassy().breakPact();
                first.viewForEmpire(second).embassy().declareWar();
                var arena = java.util.Arrays.stream(galaxy.starSystems())
                        .filter(system -> system != null && !system.isColonized() && !system.hasMonster())
                        .findFirst().orElseThrow();
                galaxy.ships.buildShips(first.id, arena.id, first.shipLab().fighterDesign().id(), 40);
                galaxy.ships.buildShips(second.id, arena.id, second.shipLab().fighterDesign().id(), 3);
                galaxy.shipCombat().battle(arena);
                var target = galaxy.system(second.homeSysId());
                target.colony().setPopulation(1);
                target.colony().defense().bases(0);
                var transport = new rotp.model.galaxy.Transport(galaxy.system(first.homeSysId()));
                transport.size(40);
                transport.setDest(target);
                transport.launch();
                target.colony().resistTransport(transport);
                assertEquals(first.id, target.empId());
                recorder.capture();
                recorder.finish();
            }
            try (var archive = new ReplayArchive(path)) {
                assertEquals(archive.frame(0).turn(), archive.frame(1).turn());
                var entries = archive.frame(1).events();
                assertTrue(entries.stream().anyMatch(e -> e.kind().equals("Reply") && e.details().startsWith("Refused")));
                var treaties = entries.stream().filter(e -> e.kind().equals("Treaty")).toList();
                assertTrue(treaties.get(0).details().startsWith("TreatyPact"));
                assertTrue(treaties.get(1).details().startsWith("TreatyNone"));
                var battles = entries.stream().filter(e -> e.kind().equals("Battle")).toList();
                assertEquals(1, battles.size());
                assertTrue(battles.get(0).details().contains("× 40"));
                assertTrue(battles.get(0).details().contains("× 3"));
                assertTrue(battles.get(0).details().contains("Victor:"));
                assertTrue(entries.stream().anyMatch(e -> e.kind().equals("Invasion")));
                assertTrue(entries.stream().anyMatch(e -> e.kind().equals("System history") && e.empire() == first.id));
            }
            System.exit(0);
        }
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
