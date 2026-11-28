package rotp.leader;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Provider-reported usage only. Partial totals carry an explicit coverage count. */
final class MatchMetrics {
    static final class Usage {
        Long input, output;
        void read(JsonObject usage, String in, String out) {
            if (usage == null) return;
            input = count(usage, in);
            output = count(usage, out);
        }

        private static Long count(JsonObject usage, String key) {
            var value = usage.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
            try {
                long tokens = value.getAsBigDecimal().longValueExact();
                return tokens >= 0 ? tokens : null;
            } catch (ArithmeticException | NumberFormatException invalidCount) {
                return null;
            }
        }
    }

    private static final class Counts {
        int attempts, retries, failures, missingInput, missingOutput;
        long inferenceMillis;
        Long inputTokens, outputTokens;
        Map<String, Integer> byPurpose = new TreeMap<>();
        void add(String purpose, boolean retry, boolean success, long nanos, Usage usage) {
            attempts++;
            if (retry) retries++;
            if (!success) failures++;
            byPurpose.merge(purpose, 1, Integer::sum);
            inferenceMillis += nanos / 1_000_000;
            if (usage.input == null) missingInput++;
            else inputTokens = (inputTokens == null ? 0 : inputTokens) + usage.input;
            if (usage.output == null) missingOutput++;
            else outputTokens = (outputTokens == null ? 0 : outputTokens) + usage.output;
        }
    }

    private final Counts total = new Counts();
    private final Map<Integer, Counts> empires = new TreeMap<>();
    final long started = System.nanoTime();

    MatchMetrics(MatchConfig config) {
        for (int id = 0; id < config.seats().size(); id++) empires.put(id, new Counts());
    }

    void add(int seat, String purpose, boolean retry, boolean success, long nanos, Usage usage) {
        empires.get(seat).add(purpose, retry, success, nanos, usage);
        total.add(purpose, retry, success, nanos, usage);
    }

    Path write(Path replay, long videoStart, int turns, String scheduler) throws IOException {
        Path file = replay.resolveSibling(replay.getFileName().toString().replaceFirst("(?i)\\.rotpreplay$", "") + ".metrics.json");
        var report = Map.of("empires", empires, "total", total, "turns", turns, "scheduler", scheduler,
                "simulationMillis", (videoStart - started) / 1_000_000,
                "videoExportMillis", (System.nanoTime() - videoStart) / 1_000_000);
        Files.writeString(file, new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report));
        return file;
    }
}
