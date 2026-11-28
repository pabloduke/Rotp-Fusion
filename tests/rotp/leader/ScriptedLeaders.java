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
        LeaderClient client = (seat, persona, request, prompt) -> {
            if (prompt.startsWith("Rank") && seat.name().equals("c")) {
                failedDrafts[0]++;
                throw new java.net.http.HttpTimeoutException("Scripted timeout");
            }
            if (prompt.startsWith("Rank")) return JsonParser.parseString(
                    "{\"preferences\":[\"RACE_PSILON\",\"RACE_HUMAN\"],\"summary\":\"I choose discovery.\"}").getAsJsonObject();
            var view = JsonParser.parseString(prompt.substring(0, prompt.indexOf('\n'))).getAsJsonObject();
            if (seat.name().equals("a") && invalid[0]++ == 0) return new JsonObject();
            if (seat.name().equals("a") && invalid[0] == 2)
                assertTrue(prompt.contains("Expected short text in summary"));
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

    public static void eventDriven(Path dir, Supplier<GameSession> start) throws Exception {
        var config = new MatchConfig("small", 7, Map.of(), List.of(seat("a"), seat("b"), seat("c")), "event");
        var calls = new HashMap<String, List<Integer>>();
        LeaderClient client = (seat, persona, kind, prompt) -> {
            var view = JsonParser.parseString(prompt.lines().findFirst().orElseThrow()).getAsJsonObject();
            int turn = view.get("turn").getAsInt();
            calls.computeIfAbsent(seat.name(), name -> new ArrayList<>()).add(turn);
            assertFalse(view.has("surveyedSystems"));
            if (seat.name().equals("c")) assertFalse(prompt.contains("Private promise"));
            var response = new JsonObject();
            if (seat.name().equals("b") && turn == 4 && kind == LeaderClient.Request.REVIEW) {
                response.add("detailSections", JsonParser.parseString("[\"systems\",\"mail/0\"]"));
                return response;
            }
            if (kind == LeaderClient.Request.FOLLOWUP) {
                assertEquals(4, turn);
                assertTrue(prompt.contains("Requested detail:"));
                assertTrue(view.getAsJsonArray("privateMail").toString().contains("Private promise"));
            }
            response.addProperty("summary", "Hold our research course.");
            response.add("orders", new JsonArray());
            if (turn == 1) response.add("allocation", JsonParser.parseString("[10,10,10,10,10,10]"));
            if (seat.name().equals("a") && turn < 4) {
                var mail = new JsonArray();
                mail.add(action("message/to/1", "Private promise " + turn));
                response.add("diplomacy", mail);
                response.addProperty("reviewAfter", 1);
            }
            if (turn == 4 && seat.name().equals("b")) {
                assertTrue(view.getAsJsonArray("activeObjectives").toString().contains("allocation"));
                response.add("cancel", JsonParser.parseString("[\"allocation\"]"));
            }
            return response;
        };
        var path = dir.resolve("event.rotpreplay");
        try (var match = new LeaderMatch(config, dir.resolve("match.json"), client)) {
            var session = start.get();
            var galaxy = session.galaxy();
            var a = galaxy.empire(0);
            var b = galaxy.empire(1);
            a.viewForEmpire(b).embassy().contact(true);
            b.viewForEmpire(a).embassy().contact(true);
            try (var recorder = new ReplayRecorder(path, session)) {
                for (int i = 1; i <= 5; i++) {
                    match.decide(galaxy);
                    recorder.capture();
                    if (i < 5) session.advanceInProcessTurn();
                }
                recorder.finish();
            }
            assertEquals(List.of(1, 4, 4, 5), calls.get("b"));
            assertEquals(List.of(1), calls.get("c"));
            var report = JsonParser.parseString(java.nio.file.Files.readString(
                    match.writeMetrics(path, System.nanoTime(), 5))).getAsJsonObject();
            var total = report.getAsJsonObject("total");
            assertEquals(total.get("attempts"), total.get("missingInput"));
            assertTrue(total.get("inputTokens").isJsonNull());
            assertEquals(1, total.getAsJsonObject("byPurpose").get("FOLLOWUP").getAsInt());
        }
        try (var replay = new ReplayArchive(path)) {
            assertTrue(replay.frame(3).events().stream().anyMatch(e -> e.details().contains("Released allocation")));
        }
    }

    public static void objectiveLifecycle(Path dir, Supplier<GameSession> start) throws Exception {
        var stock = new MatchConfig.Seat("fusion", null, null, null, null, null, null);
        var config = new MatchConfig("small", 7, Map.of(), List.of(seat("a"), stock, stock), "event");
        var calls = new ArrayList<Integer>();
        String[] project = new String[1];
        LeaderClient client = (seat, persona, kind, prompt) -> {
            var view = JsonParser.parseString(prompt.lines().findFirst().orElseThrow()).getAsJsonObject();
            int turn = view.get("turn").getAsInt();
            calls.add(turn);
            if (turn == 3) throw new java.io.IOException("fixture unavailable");
            var response = new JsonObject();
            response.addProperty("summary", "Pursue scientific advantage.");
            var orders = new JsonArray();
            if (turn == 1) {
                for (var f : view.getAsJsonArray("research")) {
                    var choices = f.getAsJsonObject().getAsJsonArray("choices");
                    if (choices.size() > 0) {
                        project[0] = choices.get(0).getAsString();
                        orders.add(project[0]);
                        break;
                    }
                }
                assertNotNull(project[0]);
                response.addProperty("reviewAfter", 2);
            } else if (turn == 4) {
                assertTrue(view.get("triggers").toString().contains("unresolved"));
                assertTrue(view.get("activeObjectives").toString().contains(project[0]));
            } else if (turn == 7) {
                assertTrue(view.get("triggers").toString().contains("Completed " + project[0]));
                assertFalse(view.get("activeObjectives").toString().contains(project[0]));
            }
            response.add("orders", orders);
            return response;
        };
        try (var match = new LeaderMatch(config, dir.resolve("match.json"), client)) {
            var galaxy = start.get().galaxy();
            for (int i = 1; i <= 7; i++) {
                if (i == 7) galaxy.empire(0).tech().learnTech(project[0].substring("research/".length()));
                match.decide(galaxy);
                galaxy.advanceTime();
            }
            assertEquals(List.of(1, 3, 3, 4, 7), calls);
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
