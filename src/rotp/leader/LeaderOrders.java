package rotp.leader;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import rotp.model.empires.Empire;
import rotp.model.galaxy.ShipFleet;

/** Orders live only in the synchronous simulation. The snapshots contain their game effects. */
public final class LeaderOrders implements AutoCloseable {
    private static final ThreadLocal<LeaderOrders> active = new ThreadLocal<>();
    private final Map<Empire, int[]> budgets = new HashMap<>();
    private final Map<ShipFleet, Integer> destinations = new IdentityHashMap<>();

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
