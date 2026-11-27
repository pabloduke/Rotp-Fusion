package rotp.leader;

import com.google.gson.JsonObject;
import java.util.*;
import rotp.model.empires.Empire;
import rotp.model.galaxy.Galaxy;
import rotp.replay.ReplayRecorder;
import rotp.ui.diplomacy.DiplomaticReply;

/** Mail is delivered at a turn boundary, before any seat's briefing is constructed. */
public final class LeaderDiplomacy implements AutoCloseable {
    private static final ThreadLocal<LeaderDiplomacy> active = new ThreadLocal<>();
    private final Set<Integer> leaders;
    private final Map<Integer, Offer> outstanding = new LinkedHashMap<>();
    private final List<Letter> queued = new ArrayList<>();
    private final Map<Integer, List<String>> history = new HashMap<>();
    private int nextId = 1;

    private record Offer(int id, int from, int to, String terms) {}
    private record Letter(int from, int to, String text, Offer offer) {}
    record Action(String choice, String text) {}
    record Choice(int other, String terms, int offer) {}
    record Menu(Map<String, Choice> choices, List<String> recentMail, List<String> openOffers) {
        List<Action> parse(JsonObject json) {
            if (!json.has("diplomacy")) return List.of();
            var result = new ArrayList<Action>();
            var used = new HashSet<String>();
            var answers = new HashSet<Integer>();
            var actions = json.getAsJsonArray("diplomacy");
            if (actions.size() > 16) throw new IllegalArgumentException("Too many diplomatic actions");
            for (var element : actions) {
                var object = element.getAsJsonObject();
                String key = LeaderBriefing.text(object, "choice");
                Choice choice = choices.get(key);
                if (choice == null || !used.add(key) || choice.offer > 0 && !answers.add(choice.offer))
                    throw new IllegalArgumentException("Unknown or conflicting diplomatic choice");
                result.add(new Action(key, LeaderBriefing.text(object, "text")));
            }
            return result;
        }
    }

    LeaderDiplomacy(Set<Integer> leaders) { this.leaders = Set.copyOf(leaders); active.set(this); }
    public static boolean controls(Empire empire) {
        var mail = active.get();
        return mail != null && mail.leaders.contains(empire.id);
    }

    /** Native Fusion offers use the same inbox as model-originated offers. */
    public static boolean defer(Empire receiver, Empire sender, String terms) {
        if (!controls(receiver)) return false;
        active.get().offer(sender.id, receiver.id, terms, "Fusion proposes " + terms);
        return true;
    }

    public static boolean forwardJointWarRequest(Empire receiver, Empire sender, Empire target) {
        if (!controls(receiver)) return false;
        active.get().send(sender.id, receiver.id, "Fusion requests that you join its war against #" + target.id, null);
        return true;
    }

    private void send(int from, int to, String text, Offer offer) {
        if (leaders.contains(from)) remember(from, "To #" + to + ": " + text);
        queued.add(new Letter(from, to, text, offer));
        ReplayRecorder.event("Diplomatic mail", from, -1, "To #" + to + ": " + text);
    }

    private void remember(int id, String text) {
        var messages = history.computeIfAbsent(id, seat -> new ArrayList<>());
        messages.add(text);
        if (messages.size() > 24) messages.remove(0);
    }

    private void offer(int from, int to, String terms, String text) {
        for (var existing : outstanding.values())
            if (existing.from == from && existing.to == to && existing.terms.equals(terms)) return;
        var offer = new Offer(nextId++, from, to, terms);
        outstanding.put(offer.id, offer);
        send(from, to, "Offer #" + offer.id + " (" + terms + "): " + text, offer);
    }

    Set<Integer> deliver(Galaxy galaxy) {
        var incoming = List.copyOf(queued);
        queued.clear();
        var recipients = new HashSet<Integer>();
        for (var letter : incoming) {
            if (leaders.contains(letter.to)) {
                remember(letter.to, "From #" + letter.from + ": " + letter.text);
                recipients.add(letter.to);
            } else if (letter.offer != null) {
                var offer = letter.offer;
                if (outstanding.remove(offer.id) == null) continue;
                var sender = galaxy.empire(offer.from);
                var receiver = galaxy.empire(offer.to);
                if (!legal(sender, receiver).contains(offer.terms)) {
                    invalid(galaxy, offer);
                    continue;
                }
                var reply = stockReply(receiver, sender, offer.terms);
                send(receiver.id, sender.id, "Offer #" + offer.id + (reply != null && reply.accepted()
                        ? " accepted by Fusion" : " declined by Fusion"), null);
            }
        }
        // Remove stale offers even if neither participant tries to accept.
        for (var offer : List.copyOf(outstanding.values())) {
            if (!legal(galaxy.empire(offer.from), galaxy.empire(offer.to)).contains(offer.terms)) {
                outstanding.remove(offer.id);
                invalid(galaxy, offer);
            }
        }
        return recipients;
    }

    private void invalid(Galaxy galaxy, Offer offer) {
        var from = galaxy.empire(offer.from);
        var to = galaxy.empire(offer.to);
        var view = from.viewForEmpire(to);
        String reason;
        if (from.extinct() || to.extinct()) reason = "an empire has been eliminated";
        else if (!view.embassy().contact()) reason = "contact has been lost";
        else if (!view.diplomats()) reason = "diplomatic channels have closed";
        else if (offer.terms.startsWith("trade/")) reason = "the trade level is no longer available";
        else reason = "the current treaty is " + view.embassy().treaty().getClass().getSimpleName();
        String result = "Offer #" + offer.id + " (" + offer.terms + ") expired: " + reason;
        send(offer.from, offer.to, result, null);
        send(offer.to, offer.from, result, null);
    }

    private static Set<String> legal(Empire from, Empire to) {
        var terms = new LinkedHashSet<String>();
        if (from.extinct() || to.extinct()) return terms;
        var view = from.viewForEmpire(to);
        if (view == null || !view.embassy().contact()) return terms;
        var embassy = view.embassy();
        terms.add("message");
        if (embassy.finalWar() || embassy.unity()) return terms;
        var diplomat = from.diplomatAI();
        if (from.options().canStartWar(from, to) && !embassy.peaceTreatyInEffect() && diplomat.canDeclareWar(to))
            terms.add("war");
        if (!view.diplomats()) return terms;
        if (embassy.war()) {
            if (from.options().canStopWar()) terms.add("peace");
            return terms;
        }
        if (diplomat.canOfferTradeTreaty(to))
            for (int level : view.nominalTradeLevels()) terms.add("trade/" + level);
        if (diplomat.canOfferPact(to)) terms.add("pact");
        if (diplomat.canOfferAlliance(to)) terms.add("alliance");
        return terms;
    }

    Menu menu(Empire empire) {
        var choices = new LinkedHashMap<String, Choice>();
        for (var v : empire.contacts()) {
            var other = v.empireUncut();
            for (String terms : legal(empire, other))
                choices.put(terms + "/to/" + other.id, new Choice(other.id, terms, 0));
        }
        var offers = new ArrayList<String>();
        for (var offer : outstanding.values()) {
            if (offer.from == empire.id || offer.to == empire.id)
                offers.add("#" + offer.id + " from #" + offer.from + " to #" + offer.to + ": " + offer.terms);
            if (offer.to != empire.id) continue;
            for (String verb : List.of("accept", "refuse"))
                choices.put(verb + "/" + offer.id, new Choice(offer.from, verb, offer.id));
        }
        return new Menu(choices, List.copyOf(history.getOrDefault(empire.id, List.of())), offers);
    }

    void apply(Galaxy galaxy, Empire from, Menu menu, List<Action> actions) {
        for (var action : actions) {
            var choice = menu.choices.get(action.choice);
            var other = galaxy.empire(choice.other);
            if (choice.offer != 0) {
                var offer = outstanding.remove(choice.offer);
                if (offer == null) continue;
                if (choice.terms.equals("accept") && !legal(other, from).contains(offer.terms)) {
                    invalid(galaxy, offer);
                    continue;
                }
                if (choice.terms.equals("accept")) enact(from, other, offer.terms);
                send(from.id, other.id, choice.terms + " offer #" + offer.id + ": " + action.text, null);
            } else if (!legal(from, other).contains(choice.terms)) {
                String result = "Order " + action.choice + " became invalid after earlier orders";
                send(other.id, from.id, result, null);
                send(from.id, other.id, result, null);
            } else if (choice.terms.equals("war") || choice.terms.equals("message")) {
                if (choice.terms.equals("war")) from.viewForEmpire(other).embassy().declareWar();
                send(from.id, other.id, choice.terms + ": " + action.text, null);
            } else offer(from.id, other.id, choice.terms, action.text);
        }
    }

    private static void enact(Empire receiver, Empire sender, String terms) {
        var embassy = receiver.viewForEmpire(sender).embassy();
        switch (terms) {
            case "peace" -> embassy.signPeace();
            case "pact" -> embassy.signPact();
            case "alliance" -> embassy.signAlliance();
            default -> embassy.establishTradeTreaty(tradeLevel(terms));
        }
    }

    private static DiplomaticReply stockReply(Empire stock, Empire sender, String terms) {
        var diplomat = stock.diplomatAI();
        return switch (terms) {
            case "peace" -> diplomat.receiveOfferPeace(sender);
            case "pact" -> diplomat.receiveOfferPact(sender);
            case "alliance" -> diplomat.receiveOfferAlliance(sender);
            default -> diplomat.receiveOfferTrade(sender, tradeLevel(terms));
        };
    }

    private static int tradeLevel(String terms) { return Integer.parseInt(terms.substring("trade/".length())); }

    @Override public void close() { active.remove(); }
}
