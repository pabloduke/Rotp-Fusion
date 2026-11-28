package rotp.leader;

import java.util.*;

/** Owned by exactly one seat. Keys refer to its own objectives and diplomatic partners. */
final class StrategicMemory {
    record Entry(int turn, String text, Set<String> topics) {}
    private final List<Entry> entries = new ArrayList<>();

    void remember(int turn, String text, Collection<String> topics) {
        entries.add(new Entry(turn, text, Set.copyOf(topics)));
    }

    List<Entry> recall(Collection<String> topics) {
        var relevant = new ArrayList<Entry>();
        for (int i = entries.size() - 1; i >= 0 && relevant.size() < 3; i--) {
            var entry = entries.get(i);
            if (i == entries.size() - 1 || !Collections.disjoint(entry.topics, topics)) relevant.add(entry);
        }
        return relevant;
    }

    List<Entry> history() { return List.copyOf(entries); }
}
