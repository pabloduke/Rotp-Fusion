package rotp.leader;

import java.util.*;
import rotp.model.empires.Empire;
import rotp.model.galaxy.ShipFleet;

/** Orders live only in the synchronous simulation. The snapshots contain their game effects. */
public final class LeaderOrders implements AutoCloseable {
    private static final ThreadLocal<LeaderOrders> active = new ThreadLocal<>();
    private final Map<Empire, int[]> budgets = new HashMap<>();
    private final Map<ShipFleet, Integer> destinations = new IdentityHashMap<>();

    private static final class Goal {
        final String id;
        final ShipFleet fleet;
        final int target;
        boolean threatened;
        Goal(String id, ShipFleet fleet, int target) {
            this.id = id;
            this.fleet = fleet;
            this.target = target;
        }
    }

    private final Map<Empire, Map<String, Goal>> goals = new HashMap<>();

    Set<String> objectives(Empire empire) {
        return Set.copyOf(goals.getOrDefault(empire, Map.of()).keySet());
    }

    private int nextGoal = 1;

    void track(Empire empire, String id, ShipFleet fleet, int target) {
        var activeGoals = goals.computeIfAbsent(empire, key -> new TreeMap<>());
        if (fleet != null) {
            activeGoals.values().removeIf(goal -> goal.fleet == fleet);
            id += "/" + nextGoal++;
        } else if (id.startsWith("research/")) {
            activeGoals.values().removeIf(goal -> goal.id.startsWith("research/") && goal.target == target);
        }
        activeGoals.put(id, new Goal(id, fleet, target));
    }

    void cancel(Empire empire, String id) {
        var goal = goals.get(empire).remove(id);
        if (goal.fleet != null) destinations.remove(goal.fleet);
        if (id.equals("allocation")) budgets.remove(empire);
    }

    List<String> evaluate(Empire empire) {
        var events = new ArrayList<String>();
        var it = goals.getOrDefault(empire, Map.of()).values().iterator();
        while (it.hasNext()) {
            Goal goal = it.next();
            String id = goal.id;
            boolean done, blocked;
            if (id.equals("allocation")) {
                done = !budgets.containsKey(empire);
                blocked = false;
            } else if (id.startsWith("research/")) {
                String tech = id.substring("research/".length());
                done = empire.tech().category(goal.target).knownTechs().contains(tech);
                blocked = !tech.equals(empire.tech().category(goal.target).currentTech());
            } else {
                int system = goal.target;
                int owner = empire.sv.empId(system);
                boolean scouted = empire.sv.isScouted(system);
                done = id.startsWith("scout/") ? scouted : owner == empire.id;
                blocked = !onMission(goal.fleet, system)
                        || (id.startsWith("colonize/") && (owner >= 0 || empire.sv.isGuarded(system)))
                        || (id.startsWith("attack/") && (owner < 0 || !empire.atWarWith(owner)));
                if (!done && !blocked) {
                    boolean threat = empire.enemyFleets().stream().anyMatch(f -> empire.atWarWith(f.empId())
                            && (f.isOrbiting() ? f.sysId() == system : empire.knowShipETA() && f.destSysId() == system));
                    if (threat && !goal.threatened) events.add("Threat to " + id);
                    goal.threatened = threat;
                }
            }
            if (done || blocked) {
                events.add((done ? "Completed " : "Blocked ") + id);
                if (goal.fleet != null) destinations.remove(goal.fleet);
                it.remove();
            }
        }
        return events;
    }

    LeaderOrders() { active.set(this); }

    void allocation(Empire empire, int[] shares) {
        budgets.put(empire, shares);
        applyAllocation(empire);
    }

    public static boolean applyAllocation(Empire empire) {
        var orders = active.get();
        var shares = orders == null ? null : orders.budgets.get(empire);
        if (shares == null) return false;
        for (int i = 0; i < shares.length; i++) {
            if (shares[i] > 0 && empire.tech().category(i).researchCompleted()) {
                orders.budgets.remove(empire);
                rotp.replay.ReplayRecorder.event("Leader order", empire.id, -1,
                        "Research field completed. Fusion will distribute the released budget.");
                return false;
            }
        }
        for (int i = 0; i < shares.length; i++) empire.tech().category(i).allocation(shares[i]);
        return true;
    }

    void assign(ShipFleet fleet, int system) { destinations.put(fleet, system); }

    void releaseArrivals() {
        destinations.entrySet().removeIf(e -> !onMission(e.getKey(), e.getValue()));
    }

    private static boolean onMission(ShipFleet fleet, int system) {
        return !fleet.isEmpty() && !fleet.retreating() && (fleet.inTransit() || fleet.isDeployed())
                && fleet.destSysId() == system;
    }

    public static boolean assigned(ShipFleet fleet) {
        var orders = active.get();
        var system = orders == null ? null : orders.destinations.get(fleet);
        return system != null && onMission(fleet, system);
    }

    @Override public void close() { active.remove(); }
}
