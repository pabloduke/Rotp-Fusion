package rotp.replay;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import rotp.model.game.GameSession;
import rotp.model.tech.TechTree;
import rotp.multiplayer.session.MatchOutcome;

/** Detached observer data. Playback has no dependency on a running GameSession. */
public record ReplayFrame(int turn, String outcome, List<Item> items, List<Event> events) {
    /** The archive stores the victory cause and the winning seats' names together. */
    public record Result(String condition, String winners) {
        public String summary() {
            return condition + " — " + (winners.isBlank() ? "No recorded winner" : "Won by " + winners);
        }
    }

    public Result result() {
        if (outcome.isBlank()) return null;
        String[] parts = outcome.split(" — ", 2);
        String condition = switch (parts[0]) {
            case "MILITARY" -> "Military victory";
            case "MILITARY_ALLIANCE" -> "Military alliance victory";
            case "COUNCIL" -> "Galactic Council victory";
            case "NO_HUMANS_REMAIN" -> "No human empires remain";
            default -> parts[0];
        };
        return new Result(condition, parts.length == 2 ? parts[1] : "");
    }

    public record Item(String kind, String name, int empire, int color, float x, float y, String details) {
        @Override public String toString() { return name; }
    }
    public record Event(int turn, String phase, String kind, int empire, int system, String details) {
        @Override public String toString() { return kind + " · " + details; }
    }

    static ReplayFrame capture(GameSession session, List<Event> journal) {
        var galaxy = session.galaxy();
        var items = new ArrayList<Item>();
        var result = session.matchOutcome();
        var winners = new ArrayList<String>();
        for (var seat : session.controllerRegistry().seats()) {
            var empire = galaxy.empire(seat.empireId());
            int color = empire.color().getRGB();
            var info = new StringBuilder(empire.extinct() ? "Eliminated" : "Active");
            for (int i = 0; i < TechTree.NUM_CATEGORIES; i++) {
                var category = empire.tech().category(i);
                info.append("\n").append(category.id()).append(": ").append(category.currentTech());
                if (!category.knownTechs().isEmpty())
                    info.append(" (known: ").append(String.join(", ", category.knownTechs())).append(")");
            }
            for (int i = 0; i < galaxy.numEmpires(); i++) {
                var view = empire.viewForEmpire(i);
                if (view != null && view.embassy().contact())
                    info.append("\n").append(galaxy.empire(i).name()).append(": ").append(view.embassy().treatyStatus());
            }
            items.add(new Item("Empire", empire.name(), empire.id, color, 0, 0, info.toString()));
            if (result.resultsByPlayer().get(seat.playerId()) == MatchOutcome.SeatResult.WON)
                winners.add(empire.name());
            for (var fleet : empire.allFleets()) {
                String details = "Origin system #" + fleet.sysId() + "\nShips by design slot: " +
                        java.util.Arrays.toString(fleet.numCopy());
                if (fleet.hasDestination()) details += "\nDestination system #" + fleet.destSysId();
                items.add(new Item("Fleet", empire.name() + " fleet (" + fleet.numShips() + " ships)",
                        empire.id, color, fleet.x(), fleet.y(), details));
            }
        }
        for (int i = 0; i < galaxy.numStarSystems(); i++) {
            var system = galaxy.system(i);
            var colony = system.colony();
            String details = colony == null ? "Uncolonized" : colony.empire().name() +
                    "\nPopulation: " + colony.population() + "\nFactories: " + colony.industry().factories();
            if (colony != null) {
                var allocation = new java.util.StringJoiner(", ");
                for (int category = 0; category < 5; category++)
                    allocation.add(String.valueOf(colony.allocation(category)));
                details += "\nShip, defense, industry, ecology, research: " + allocation;
                if (colony.shipyard().design() != null)
                    details += "\nBuilding: " + colony.shipyard().design().name();
            }
            items.add(new Item("System", "#" + i + " " + system.name(), system.empId(),
                    colony == null ? 0xff999999 : colony.empire().color().getRGB(), system.x(), system.y(), details));
        }
        String outcome = result.finished() ? result.cause() + " — " + String.join(", ", winners) : "";
        return new ReplayFrame(galaxy.currentTurn(), outcome, items, List.copyOf(journal));
    }

    void write(DataOutput out) throws IOException {
        out.writeInt(turn);
        out.writeUTF(outcome);
        out.writeInt(items.size());
        for (Item item : items) {
            out.writeUTF(item.kind); out.writeUTF(item.name); out.writeInt(item.empire);
            out.writeInt(item.color); out.writeFloat(item.x); out.writeFloat(item.y);
            writeText(out, item.details);
        }
        out.writeInt(events.size());
        for (Event event : events) {
            out.writeInt(event.turn); out.writeUTF(event.phase); out.writeUTF(event.kind);
            out.writeInt(event.empire); out.writeInt(event.system); writeText(out, event.details);
        }
    }

    static ReplayFrame read(DataInput in) throws IOException {
        int turn = in.readInt();
        String outcome = in.readUTF();
        var items = new ArrayList<Item>();
        for (int i = count(in); i > 0; i--)
            items.add(new Item(in.readUTF(), in.readUTF(), in.readInt(), in.readInt(),
                    in.readFloat(), in.readFloat(), readText(in)));
        var events = new ArrayList<Event>();
        for (int i = count(in); i > 0; i--)
            events.add(new Event(in.readInt(), in.readUTF(), in.readUTF(), in.readInt(), in.readInt(), readText(in)));
        return new ReplayFrame(turn, outcome, items, events);
    }

    static int count(DataInput in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 10_000_000) throw new IOException("Invalid replay length: " + n);
        return n;
    }
    private static void writeText(DataOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
    private static String readText(DataInput in) throws IOException {
        byte[] bytes = new byte[count(in)];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
