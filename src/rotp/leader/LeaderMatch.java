package rotp.leader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import rotp.model.empires.Empire;
import rotp.model.empires.species.Species;
import rotp.model.galaxy.Galaxy;
import rotp.model.game.IGameOptions;
import rotp.replay.ReplayRecorder;

public final class LeaderMatch implements AutoCloseable {
    private record Draft(List<String> preferences, String summary) {}
    private record Entry(int seat, String kind, String details) {}
    private final List<Entry> journal = new ArrayList<>();
    private final MatchConfig config;
    private final LeaderClient client;
    private final Map<Integer, Leader> leaders = new TreeMap<>();
    private final LeaderOrders policy;
    private final LeaderDiplomacy diplomacy;

    private static final class Leader {
        final MatchConfig.Seat seat;
        final String persona;
        Set<Integer> contacts = Set.of(), wars = Set.of(), colonies = Set.of();
        String memory = "";
        Leader(MatchConfig.Seat seat, String persona) { this.seat = seat; this.persona = persona; }
    }

    public LeaderMatch(MatchConfig config, Path file) throws IOException {
        this(config, file, LeaderClient.http(config));
    }

    LeaderMatch(MatchConfig config, Path file, LeaderClient client) throws IOException {
        this.config = config;
        this.client = client;
        for (int i = 0; i < config.seats().size(); i++) {
            var seat = config.seats().get(i);
            if (seat.isLeader()) leaders.put(i, new Leader(seat, seat.loadPrompt(file.toAbsolutePath().getParent())));
        }
        policy = new LeaderOrders();
        diplomacy = new LeaderDiplomacy(leaders.keySet());
    }

    private <T> T request(int id, String prompt, Function<JsonObject, T> parse) throws InterruptedException {
        var leader = leaders.get(id);
        String feedback = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                var json = client.ask(leader.seat, leader.persona, prompt + feedback);
                return parse.apply(json);
            } catch (IOException | RuntimeException invalid) {
                // An endpoint can echo private credentials or arbitrary content in errors.
                String reason = invalid.getClass().getSimpleName();
                journal.add(new Entry(id, "Leader request failed", "Attempt " + attempt + ": " + reason));
                feedback = "\nThe previous attempt failed. Return one JSON object matching the requested fields and legal choices.";
            }
        }
        return null;
    }

    public void draft(IGameOptions options) throws InterruptedException {
        var races = new ArrayList<>(options.startingRaceOptions());
        if (races.size() < config.seats().size()) throw new IllegalArgumentException("Not enough distinct races for this draft");
        var traits = new LinkedHashMap<String, Object>();
        for (String key : races) {
            var race = new Species(key);
            traits.put(key, Map.of("name", race.setupName(), "growth", race.growthRateMod(),
                    "research", race.researchBonusPct(), "shipAttack", race.shipAttackBonus()));
        }
        String prompt = "Rank available race IDs for your empire. Return {\"preferences\":[\"race ID\"],"
                + "\"summary\":\"short in-character explanation\"}. Available races: " + new Gson().toJson(traits);
        var drafts = new HashMap<Integer, Draft>();
        for (int id : leaders.keySet()) {
            drafts.put(id, request(id, prompt, json -> {
                var preferences = new LinkedHashSet<String>();
                for (var item : json.getAsJsonArray("preferences")) {
                    String key = item.getAsString();
                    if (!traits.containsKey(key) || !preferences.add(key))
                        throw new IllegalArgumentException("Invalid race preference");
                }
                if (preferences.isEmpty()) throw new IllegalArgumentException("Rank at least one race");
                return new Draft(List.copyOf(preferences), LeaderBriefing.text(json, "summary"));
            }));
        }
        var order = new ArrayList<>(leaders.keySet());
        Collections.shuffle(order, new Random(config.draftSeed()));
        for (int i = 0; i < config.seats().size(); i++) if (!leaders.containsKey(i)) order.add(i);
        for (int id : order) {
            var draft = drafts.get(id);
            String race = draft == null ? races.get(0) : draft.preferences.stream()
                    .filter(races::contains).findFirst().orElse(races.get(0));
            races.remove(race);
            if (id == 0) options.selectedPlayerRace(race);
            else options.selectedOpponentRace(id - 1, race);
            var leader = leaders.get(id);
            String identity = leader == null ? "Fusion" : leader.seat.name() + " (" + leader.seat.model() + ")";
            String details = identity + " drafted " + race;
            if (draft != null) details += ". Ranked " + draft.preferences + ". " + draft.summary;
            journal.add(new Entry(id, "Race draft", details));
        }
    }

    public void decide(Galaxy galaxy) throws InterruptedException {
        policy.releaseArrivals();
        var delivered = diplomacy.deliver(galaxy);
        var briefs = new TreeMap<Integer, LeaderBriefing>();
        for (var entry : leaders.entrySet()) {
            int id = entry.getKey();
            Empire empire = galaxy.empire(id);
            if (empire.extinct()) continue;
            var leader = entry.getValue();
            var triggers = new ArrayList<String>();
            if ((galaxy.currentTurn() - 1) % 5 == 0) triggers.add("Scheduled strategy review");
            var contacts = new HashSet<Integer>();
            var wars = new HashSet<Integer>();
            var colonies = new HashSet<Integer>();
            for (var v : empire.contacts()) {
                int other = v.empireUncut().id;
                contacts.add(other);
                if (v.embassy().anyWar()) wars.add(other);
            }
            for (var colony : empire.allColonizedSystems()) colonies.add(colony.id);
            for (int other : contacts) if (!leader.contacts.contains(other)) triggers.add("First contact #" + other);
            for (int other : wars) if (!leader.wars.contains(other)) triggers.add("War with #" + other);
            for (int lost : leader.colonies) if (!colonies.contains(lost)) triggers.add("Lost colony #" + lost);
            leader.contacts = contacts;
            leader.wars = wars;
            leader.colonies = colonies;
            if (delivered.contains(id)) triggers.add("New diplomatic mail");
            if (!triggers.isEmpty())
                briefs.put(id, new LeaderBriefing(galaxy, empire, triggers, leader.memory, config, diplomacy.menu(empire)));
        }
        var decisions = new TreeMap<Integer, LeaderBriefing.Decision>();
        for (var entry : briefs.entrySet()) {
            int id = entry.getKey();
            var brief = entry.getValue();
            journal.add(new Entry(id, "Leader briefing", brief.observations));
            decisions.put(id, request(id, brief.prompt, brief::parse));
        }
        for (var event : journal) ReplayRecorder.event(event.kind, event.seat, -1, event.details);
        journal.clear();
        for (var entry : decisions.entrySet()) {
            int id = entry.getKey();
            if (entry.getValue() != null) leaders.get(id).memory = briefs.get(id).apply(entry.getValue(), policy, diplomacy);
            else ReplayRecorder.event("Leader strategy", id, -1, "No valid response. Retaining existing orders.");
        }
    }

    @Override public void close() { diplomacy.close(); policy.close(); }
}
