package rotp.replay;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** A conflict becomes visible on its first recorded frame and fades by game turn. */
final class ReplayConflicts {
    static final int VISIBLE_TURNS = 5;
    record Cue(int age, boolean capture) {}

    private record Mark(int firstFrame, int captureFrame) {}
    private int frameIndex = -1;
    private final Map<Integer, Map<Integer, Mark>> turns = new HashMap<>();
    private int[] owners;

    static ReplayConflicts read(ReplayArchive archive) throws IOException {
        var result = new ReplayConflicts();
        for (int i = 0; i < archive.size(); i++) result.observe(archive.frame(i));
        return result;
    }

    void observe(ReplayFrame frame) {
        frameIndex++;
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
        if (system < 0) return;
        var systems = turns.computeIfAbsent(turn, ignored -> new HashMap<>());
        var earlier = systems.get(system);
        if (earlier == null)
            systems.put(system, new Mark(frameIndex, capture ? frameIndex : -1));
        else if (capture && earlier.captureFrame() == -1)
            systems.put(system, new Mark(earlier.firstFrame(), frameIndex));
    }

    Map<Integer, Cue> at(int turn) {
        return at(turn, Integer.MAX_VALUE);
    }

    Map<Integer, Cue> at(int turn, int throughFrame) {
        var cues = new HashMap<Integer, Cue>();
        for (int age = 0; age < VISIBLE_TURNS; age++) {
            var events = turns.get(turn - age);
            if (events == null) continue;
            for (var event : events.entrySet()) {
                var mark = event.getValue();
                if (mark.firstFrame() <= throughFrame)
                    cues.putIfAbsent(event.getKey(), new Cue(age, mark.captureFrame() >= 0 && mark.captureFrame() <= throughFrame));
            }
        }
        return cues;
    }
}
