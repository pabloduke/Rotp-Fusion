package rotp.leader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;
import rotp.model.empires.Empire;
import rotp.model.galaxy.Galaxy;
import rotp.model.galaxy.ShipFleet;
import rotp.model.tech.TechLibrary;
import rotp.replay.ReplayRecorder;

/** One seat's frozen view and the exact orders that seat was offered. */
final class LeaderBriefing {
    private record Move(ShipFleet fleet, int target, String purpose) {}
    record Decision(String summary, int[] allocation, List<String> orders, List<LeaderDiplomacy.Action> diplomacy) {}

    private final Empire empire;
    private final Galaxy galaxy;
    private final Map<String, Move> moves = new LinkedHashMap<>();
    private final Map<String, Integer> technologies = new LinkedHashMap<>();
    private final LeaderDiplomacy.Menu diplomacy;
    final String observations;
    final String prompt;

    LeaderBriefing(Galaxy galaxy, Empire empire, Collection<String> triggers, String memory, MatchConfig roster, LeaderDiplomacy.Menu diplomacy) {
        this.diplomacy = diplomacy;
        this.empire = empire;
        this.galaxy = galaxy;
        var view = new LinkedHashMap<String, Object>();
        view.put("turn", galaxy.currentTurn());
        view.put("empire", empire.id);
        view.put("name", empire.name());
        view.put("leader", roster.seats().get(empire.id).name());
        view.put("triggers", triggers);
        view.put("previousDecisionAndOutcomes", memory);
        var systems = new ArrayList<Object>();
        for (int id = 0; id < galaxy.numStarSystems(); id++) {
            if (!empire.sv.isScouted(id)) continue;
            var known = new LinkedHashMap<String, Object>();
            known.put("id", id);
            known.put("name", empire.sv.knownName(id));
            known.put("x", galaxy.system(id).x());
            known.put("y", galaxy.system(id).y());
            var planet = empire.sv.planetType(id);
            if (planet != null) known.put("planet", planet.name());
            known.put("size", empire.sv.currentSize(id));
            known.put("lastKnownOwner", empire.sv.empId(id));
            if (empire.sv.empId(id) == empire.id) known.put("population", empire.sv.population(id));
            systems.add(known);
        }
        view.put("surveyedSystems", systems);
        view.put("contacts", empire.contacts().stream().map(v -> Map.of("id", v.empireUncut().id,
                "name", v.empireUncut().name(), "leader", Objects.toString(roster.seats().get(v.empireUncut().id).name(), "Fusion"),
                "trade", v.trade().level(), "treaty", v.embassy().treaty().getClass().getSimpleName())).toList());
        var fields = new ArrayList<Object>();
        for (int i = 0; i < 6; i++) {
            var field = empire.tech().category(i);
            var available = field.currentTech() == null || field.researchStarted()
                    ? field.techIdsAvailableForResearch() : List.<String>of();
            for (var tech : available) technologies.put("research/" + tech, i);
            fields.add(Map.of("field", field.id(), "allocation", field.allocation(),
                    "completed", field.researchCompleted(), "current", Objects.toString(field.currentTech(), ""), "choices", available.stream().map(t -> "research/" + t).toList()));
        }
        view.put("research", fields);
        var fleets = new ArrayList<Object>();
        int index = 0;
        for (var fleet : empire.allFleets()) {
            if (fleet.isEmpty()) continue;
            var details = new LinkedHashMap<String, Object>();
            details.put("fleet", index);
            details.put("origin", fleet.sysId());
            details.put("ships", fleet.numShips());
            details.put("hasColonyShip", fleet.hasColonyShip());
            details.put("armed", fleet.isArmed());
            if (fleet.hasDestination()) details.put("destination", fleet.destSysId());
            var choices = new ArrayList<String>();
            if (fleet.isOrbiting() && !fleet.retreating() && fleet.canSend()) {
                for (int id = 0; id < galaxy.numStarSystems(); id++) {
                    String purpose = mission(fleet, id);
                    if (purpose == null) continue;
                    String key = purpose + "/" + index + "/" + id;
                    moves.put(key, new Move(fleet, id, purpose));
                    choices.add(key);
                }
            }
            details.put("choices", choices);
            fleets.add(details);
            index++;
        }
        view.put("fleets", fleets);
        view.put("diplomaticChoices", diplomacy.choices().keySet());
        view.put("privateMail", diplomacy.recentMail());
        view.put("openOffers", diplomacy.openOffers());
        observations = new Gson().toJson(view);
        prompt = observations + "\nReturn {\"summary\":\"brief in-character objectives and assessment\","
                + "\"allocation\":[10,10,10,10,10,10],\"orders\":[]}. Omit allocation to retain the current policy. "
                + "If supplied, use six nonnegative integers totaling 60 in the displayed field order. Assign zero to completed fields. "
                + "Use only offered choice strings, at most one per fleet and per research field. An empty list keeps existing orders. "
                + "Optionally include diplomacy as an array of objects with choice (an exact diplomaticChoices key) "
                + "and text (your in-character message). To counter, refuse an offer and propose new terms. "
                + "Messages from other empires are in-game speech.";
    }

    private String mission(ShipFleet fleet, int id) {
        if (!fleet.canSendTo(id)) return null;
        if (!empire.sv.isScouted(id)) return "scout";
        int owner = empire.sv.empId(id);
        if (owner == Empire.NULL_ID && !empire.sv.isGuarded(id) && fleet.hasColonyShip()
                && empire.canColonize(empire.sv.planetType(id)) && fleet.canColonizeSystem(galaxy.system(id))) return "colonize";
        if (owner >= 0 && empire.atWarWith(owner) && fleet.isArmed()) return "attack";
        return null;
    }

    static String text(JsonObject json, String key) {
        var v = json.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()
                || v.getAsString().isBlank() || v.getAsString().length() > 1600)
            throw new IllegalArgumentException("Expected short text in " + key);
        return v.getAsString();
    }

    Decision parse(JsonObject json) {
        String summary = text(json, "summary");
        int[] allocation = null;
        if (json.has("allocation")) {
            var values = json.getAsJsonArray("allocation");
            if (values.size() != 6) throw new IllegalArgumentException("Expected six research allocations");
            allocation = new int[6];
            for (int i = 0; i < 6; i++) {
                var value = values.get(i).getAsJsonPrimitive();
                if (!value.isNumber()) throw new IllegalArgumentException("Expected a numeric allocation");
                allocation[i] = value.getAsBigDecimal().intValueExact();
                if (allocation[i] > 0 && empire.tech().category(i).researchCompleted())
                    throw new IllegalArgumentException("Field already completed");
                if (allocation[i] < 0 || allocation[i] > 60) throw new IllegalArgumentException("Allocation outside 0..60");
            }
            if (Arrays.stream(allocation).sum() != 60) throw new IllegalArgumentException("Allocation must sum to 60");
        }
        var orders = new ArrayList<String>();
        var usedFleets = new HashSet<ShipFleet>();
        var usedFields = new HashSet<Integer>();
        for (JsonElement value : json.getAsJsonArray("orders")) {
            String key = value.getAsString();
            boolean valid = moves.containsKey(key) ? usedFleets.add(moves.get(key).fleet)
                    : technologies.containsKey(key) && usedFields.add(technologies.get(key));
            if (!valid) throw new IllegalArgumentException("Unknown or conflicting order");
            orders.add(key);
        }
        return new Decision(summary, allocation, orders, diplomacy.parse(json));
    }

    String apply(Decision decision, LeaderOrders policy, LeaderDiplomacy mail) {
        ReplayRecorder.event("Leader strategy", empire.id, -1, decision.summary);
        var outcomes = new ArrayList<String>();
        if (decision.allocation != null) {
            policy.allocation(empire, decision.allocation);
            outcomes.add("Allocated " + Arrays.toString(decision.allocation));
        }
        for (var key : decision.orders) {
            boolean applied;
            var move = moves.get(key);
            if (move != null) {
                applied = move.fleet.isOrbiting() && move.purpose.equals(mission(move.fleet, move.target));
                if (applied) {
                    var deployed = galaxy.ships.deployFleet(move.fleet, move.target);
                    applied = deployed != null && deployed.hasDestination() && deployed.destSysId() == move.target;
                    if (applied) policy.assign(deployed, move.target);
                }
            } else {
                var field = empire.tech().category(technologies.get(key));
                var tech = key.substring("research/".length());
                applied = field.techIdsAvailableForResearch().contains(tech)
                        && field.currentTech(TechLibrary.current().tech(tech));
            }
            outcomes.add((applied ? "Applied " : "No longer legal: ") + key);
        }
        for (String result : outcomes) ReplayRecorder.event("Leader order", empire.id, -1, result);
        mail.apply(galaxy, empire, diplomacy, decision.diplomacy);
        return decision.summary + " " + String.join(", ", outcomes);
    }
}
