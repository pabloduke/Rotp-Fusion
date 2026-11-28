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
    private final MatchMetrics metrics;
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
        final StrategicMemory history = new StrategicMemory();
        boolean retry;
        int firstUnread, mailWindow = 3, reviewTurn;
        Set<String> topics = Set.of();
        Leader(MatchConfig.Seat seat, String persona) { this.seat = seat; this.persona = persona; }
    }

    public LeaderMatch(MatchConfig config, Path file) throws IOException {
        this(config, file, LeaderClient.forMatch(config));
    }

    LeaderMatch(MatchConfig config, Path file, LeaderClient client) throws IOException {
        this.config = config;
        metrics = new MatchMetrics(config);
        this.client = client;
        for (int i = 0; i < config.seats().size(); i++) {
            var seat = config.seats().get(i);
            if (seat.isLeader()) leaders.put(i, new Leader(seat, seat.loadPrompt(file.toAbsolutePath().getParent())));
        }
        policy = new LeaderOrders();
        diplomacy = new LeaderDiplomacy(leaders.keySet(), config.eventDriven());
    }

    private <T> T request(int id, LeaderClient.Request kind, String prompt, Function<JsonObject, T> parse) throws InterruptedException {
        var leader = leaders.get(id);
        String feedback = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            long started = System.nanoTime();
            var usage = new MatchMetrics.Usage();
            boolean success = false;
            try {
                var json = client.ask(leader.seat, leader.persona, kind, prompt + feedback, usage);
                T parsed = parse.apply(json);
                success = true;
                return parsed;
            } catch (IOException | RuntimeException invalid) {
                // An endpoint can echo private credentials or arbitrary content in errors.
                boolean validation = invalid instanceof LeaderClient.InvalidDecision;
                String reason = validation || invalid instanceof CodexExec.Failure
                        ? invalid.getMessage() : invalid.getClass().getSimpleName();
                journal.add(new Entry(id, "Leader request failed", "Attempt " + attempt + ": " + reason));
                feedback = "\nThe previous attempt failed. "
                        + (validation ? reason + ". " : "")
                        + "Return one JSON object matching the requested fields and legal choices.";
            } finally {
                metrics.add(id, kind.name(), attempt > 1, success, System.nanoTime() - started, usage);
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
            drafts.put(id, request(id, LeaderClient.Request.DRAFT, prompt, json -> {
                var preferences = new LinkedHashSet<String>();
                for (var item : json.getAsJsonArray("preferences")) {
                    String key = item.getAsString();
                    if (!traits.containsKey(key) || !preferences.add(key))
                        throw new LeaderClient.InvalidDecision("Rank distinct race IDs from the available races");
                }
                if (preferences.isEmpty()) throw new LeaderClient.InvalidDecision("Rank at least one race");
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
            String identity = leader == null ? "Fusion" : leader.seat.name() + " (" + leader.seat.modelLabel() + ")";
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
            int turn = galaxy.currentTurn();
            var topics = new TreeSet<>(policy.objectives(empire));
            if (config.eventDriven()) {
                for (String change : policy.evaluate(empire)) {
                    triggers.add(change);
                    leader.history.remember(turn, change, topics);
                    ReplayRecorder.event("Leader objective", id, -1, change);
                }
                if (leader.retry) triggers.add("Previous review unresolved");
                if (leader.reviewTurn > 0 && turn >= leader.reviewTurn) triggers.add("Requested reassessment");
                if (policy.objectives(empire).isEmpty() && leader.reviewTurn == 0) triggers.add("Choose next objective");
            }
            if (!config.eventDriven() && (turn - 1) % 5 == 0) triggers.add("Scheduled strategy review");
            var contacts = new HashSet<Integer>();
            var wars = new HashSet<Integer>();
            var colonies = new HashSet<Integer>();
            for (var v : empire.contacts()) {
                int other = v.empireUncut().id;
                contacts.add(other);
                if (v.embassy().anyWar()) wars.add(other);
            }
            for (var colony : empire.allColonizedSystems()) colonies.add(colony.id);
            for (int other : contacts) {
                if (!leader.contacts.contains(other)) {
                    triggers.add("First contact #" + other);
                    topics.add("mail/" + other);
                }
                if (wars.contains(other) && !leader.wars.contains(other)) {
                    triggers.add("War with #" + other);
                    topics.add("mail/" + other);
                }
            }
            for (int lost : leader.colonies) if (!colonies.contains(lost)) triggers.add("Lost colony #" + lost);
            leader.contacts = contacts;
            leader.wars = wars;
            leader.colonies = colonies;
            if (delivered.contains(id) && leader.firstUnread == 0) leader.firstUnread = turn;
            boolean mailDue = config.eventDriven()
                    ? leader.firstUnread > 0 && (diplomacy.urgent(id) || turn - leader.firstUnread + 1 >= leader.mailWindow)
                    : delivered.contains(id);
            if (mailDue) triggers.add("New diplomatic mail");
            diplomacy.addInboxTopics(id, topics);
            leader.topics = topics;
            if (!triggers.isEmpty())
                briefs.put(id, new LeaderBriefing(galaxy, empire, triggers, leader.memory, config, diplomacy.menu(empire),
                        policy.objectives(empire), leader.history, diplomacy, topics));
        }
        var decisions = new TreeMap<Integer, LeaderBriefing.Decision>();
        for (var entry : briefs.entrySet()) {
            int id = entry.getKey();
            var brief = entry.getValue();
            journal.add(new Entry(id, "Leader briefing", brief.observations));
            decisions.put(id, review(id, brief));
        }
        for (var event : journal) ReplayRecorder.event(event.kind, event.seat, -1, event.details);
        journal.clear();
        for (var entry : decisions.entrySet()) {
            int id = entry.getKey();
            var leader = leaders.get(id);
            var decision = entry.getValue();
            leader.retry = decision == null;
            if (decision != null) {
                leader.memory = briefs.get(id).apply(decision, policy, diplomacy);
                diplomacy.acknowledge(id);
                leader.firstUnread = 0;
                if (config.eventDriven()) {
                    if (decision.mailWindow() != null) leader.mailWindow = decision.mailWindow();
                    if (decision.reviewAfter() != null) leader.reviewTurn = galaxy.currentTurn() + decision.reviewAfter();
                    else if (leader.reviewTurn <= galaxy.currentTurn()) leader.reviewTurn = 0;
                    var topics = new TreeSet<>(leader.topics);
                    topics.addAll(policy.objectives(galaxy.empire(id)));
                    for (var action : decision.diplomacy()) {
                        var choice = briefs.get(id).counterpart(action);
                        topics.add("mail/" + choice);
                    }
                    if (!leader.memory.isBlank()) leader.history.remember(galaxy.currentTurn(), leader.memory, topics);
                }
            }
            else ReplayRecorder.event("Leader fallback", id, -1, "No valid response. Retaining existing orders.");
        }
    }

    private LeaderBriefing.Decision review(int id, LeaderBriefing brief) throws InterruptedException {
        if (!config.eventDriven())
            return request(id, LeaderClient.Request.TURN, brief.prompt, brief::parse);

        record Reply(String detail, LeaderBriefing.Decision decision) {}
        var reply = request(id, LeaderClient.Request.REVIEW, brief.prompt, json -> {
            String detail = brief.detail(json);
            return new Reply(detail, detail == null ? brief.parse(json) : null);
        });
        if (reply == null) return null;
        if (reply.detail == null) return reply.decision;
        journal.add(new Entry(id, "Leader detail", reply.detail));
        String prompt = brief.prompt + "\nRequested detail: " + reply.detail + "\nSubmit the final decision now.";
        return request(id, LeaderClient.Request.FOLLOWUP, prompt, json -> {
            if (brief.detail(json) != null) throw new LeaderClient.InvalidDecision("Detail lookup already used");
            return brief.parse(json);
        });
    }

    public Path writeMetrics(Path recording, long videoStart, int turns) throws IOException {
        return metrics.write(recording, videoStart, turns, config.scheduler());
    }

    @Override public void close() { diplomacy.close(); policy.close(); }
}
