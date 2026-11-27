package rotp.leader;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import rotp.model.empires.species.Species;
import rotp.model.game.*;
import rotp.replay.*;
import static org.junit.jupiter.api.Assertions.*;

/** Invoked in the same isolated headless JVM as the other full-engine fixtures. */
public class ScriptedLeaders {
    public static void run(Path dir, IGameOptions options, Supplier<GameSession> start) throws Exception {
        var seats = List.of(seat("a"), seat("b"), seat("c"));
        var config = new MatchConfig("small", 7, Map.of(), seats);
        var briefings = new HashMap<String, List<JsonObject>>();
        var invalid = new int[1];
        var acceptanceAttempts = new int[1];
        var failedDrafts = new int[1];
        var destination = new int[]{-1};
        LeaderClient client = (seat, persona, prompt) -> {
            if (prompt.startsWith("Rank") && seat.name().equals("c")) {
                failedDrafts[0]++;
                throw new java.net.http.HttpTimeoutException("Scripted timeout");
            }
            if (prompt.startsWith("Rank")) return JsonParser.parseString(
                    "{\"preferences\":[\"RACE_PSILON\",\"RACE_HUMAN\"],\"summary\":\"I choose discovery.\"}").getAsJsonObject();
            var view = JsonParser.parseString(prompt.substring(0, prompt.indexOf('\n'))).getAsJsonObject();
            if (seat.name().equals("a") && invalid[0]++ == 0) return new JsonObject();
            briefings.computeIfAbsent(seat.name(), s -> new ArrayList<>()).add(view);
            var fields = new JsonObject();
            fields.addProperty("summary", "Protect our colonies");
            var orders = new JsonArray();
            var actions = new JsonArray();
            fields.add("orders", orders);
            fields.add("diplomacy", actions);
            int turn = view.get("turn").getAsInt();
            if (turn == 1 && seat.name().equals("a")) {
                fields.add("allocation", JsonParser.parseString("[0,0,0,0,60,0]"));
                outer: for (var fleet : view.getAsJsonArray("fleets"))
                    for (var choice : fleet.getAsJsonObject().getAsJsonArray("choices"))
                        if (choice.getAsString().startsWith("colonize/")) {
                            orders.add(choice);
                            destination[0] = Integer.parseInt(choice.getAsString().split("/")[2]);
                            break outer;
                        }
                actions.add(action("message/to/1", "Private invitation"));
                actions.add(action("peace/to/1", "End our war"));
            }
            if (seat.name().equals("b") && turn > 1) {
                for (var choice : view.getAsJsonArray("diplomaticChoices")) {
                    if (!choice.getAsString().startsWith("accept/")) continue;
                    actions.add(action(choice.getAsString(), "Agreed"));
                    if (acceptanceAttempts[0]++ == 0) actions.add(action(choice.getAsString(), "Agreed again"));
                }
            }
            return fields;
        };
        Path archive = dir.resolve("leaders.rotpreplay");
        try (var match = new LeaderMatch(config, dir.resolve("match.json"), client)) {
            match.draft(options);
            assertEquals(2, failedDrafts[0]);
            var session = start.get();
            var g = session.galaxy();
            var a = g.empire(0);
            var b = g.empire(1);
            assertEquals(new Species(options.selectedPlayerRace()).id(), a.id());
            assertEquals(new Species(options.selectedOpponentRace(0)).id(), b.id());
            assertEquals(3, Set.of(a.id(), b.id(), g.empire(2).id()).size());
            a.viewForEmpire(b).embassy().contact(true);
            b.viewForEmpire(a).embassy().contact(true);
            var embassy = a.viewForEmpire(b).embassy();
            embassy.declareWar();
            embassy.openEmbassy();
            embassy.otherEmbassy().openEmbassy();
            // Make a known reachable colony target using only real map information.
            var colonyFleet = a.allFleets().stream().filter(f -> f.hasColonyShip()).findFirst().orElseThrow();
            for (int id = 0; id < g.numStarSystems(); id++) {
                var system = g.system(id);
                if (system.isColonized() || system.hasMonster() || !colonyFleet.canSendTo(id)
                        || !colonyFleet.canColonizeSystem(system)) continue;
                a.sv.refreshFullScan(id);
                break;
            }
            try (var recorder = new ReplayRecorder(archive, session)) {
                match.decide(g);
                assertTrue(destination[0] >= 0, "Expected a reachable colony target");
                assertTrue(a.allFleets().stream().anyMatch(f -> f.isDeployed() && f.destSysId() == destination[0]));
                assertFalse(briefings.get("b").get(0).toString().contains("Private invitation"));
                var hiddenSystems = briefings.get("a").get(0).getAsJsonArray("surveyedSystems");
                for (var s : hiddenSystems) assertTrue(a.sv.isScouted(s.getAsJsonObject().get("id").getAsInt()));
                recorder.capture();
                session.advanceInProcessTurn();
                assertEquals(60, a.tech().category(4).allocation());
                assertTrue(a.allFleets().stream().anyMatch(f -> f.inTransit() && f.destSysId() == destination[0])
                        || g.system(destination[0]).empId() == a.id);
                match.decide(g);
                assertTrue(briefings.get("b").get(1).getAsJsonArray("privateMail").toString().contains("Private invitation"));
                assertEquals(2, acceptanceAttempts[0]);
                assertFalse(embassy.anyWar());
                assertFalse(briefings.get("c").toString().contains("Private invitation"));
                recorder.capture();
                recorder.finish();
            }
        }
        try (var replay = new ReplayArchive(archive)) {
            assertEquals(1, replay.frame(1).events().stream()
                    .filter(e -> e.kind().equals("Diplomatic mail") && e.details().contains("accept offer #")).count());
            assertEquals(3, replay.frame(0).events().stream().filter(e -> e.kind().equals("Race draft")).count());
            assertEquals(3, replay.frame(0).events().stream().filter(e -> e.kind().equals("Leader request failed")).count());
            assertTrue(replay.frame(0).events().stream().anyMatch(e -> e.details().startsWith("Applied colonize/")));
        }
    }

    private static MatchConfig.Seat seat(String name) {
        return new MatchConfig.Seat("llm", name, name, "scripted", "data", null, null);
    }

    private static JsonObject action(String choice, String text) {
        var action = new JsonObject();
        action.addProperty("choice", choice);
        action.addProperty("text", text);
        return action;
    }
}
