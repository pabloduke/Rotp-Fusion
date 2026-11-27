package rotp.replay;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Sparse index of recorded conflicts. A lookup depends only on the requested game turn. */
final class ReplayConflicts {
    static final int VISIBLE_TURNS = 5;
    record Cue(int age, boolean capture) {}

    private final Map<Integer, Map<Integer, Boolean>> turns = new HashMap<>();
    private int[] owners;

    static ReplayConflicts read(ReplayArchive archive) throws IOException {
        var result = new ReplayConflicts();
        for (int i = 0; i < archive.size(); i++) result.observe(archive.frame(i));
        return result;
    }

    void observe(ReplayFrame frame) {
        for (var event : frame.events()) {
            switch (event.kind()) {
                case "Battle", "Bombardment", "Invasion" -> mark(event.turn(), event.system(), false);
            }
        }
        // Observer frames store all systems in galaxy ID order, including uncolonized systems.
        int[] current = frame.items().stream().filter(item -> item.kind().equals("System"))
                .mapToInt(ReplayFrame.Item::empire).toArray();
        for (int i = 0; owners != null && i < current.length; i++) {
            if (owners[i] >= 0 && current[i] >= 0 && owners[i] != current[i])
                mark(frame.turn(), i, true);
        }
        owners = current;
    }

    private void mark(int turn, int system, boolean capture) {
        if (system >= 0)
            turns.computeIfAbsent(turn, ignored -> new HashMap<>()).merge(system, capture, Boolean::logicalOr);
    }

    Map<Integer, Cue> at(int turn) {
        var cues = new HashMap<Integer, Cue>();
        for (int age = 0; age < VISIBLE_TURNS; age++) {
            var events = turns.get(turn - age);
            if (events == null) continue;
            for (var event : events.entrySet())
                cues.putIfAbsent(event.getKey(), new Cue(age, event.getValue()));
        }
        return cues;
    }
}
