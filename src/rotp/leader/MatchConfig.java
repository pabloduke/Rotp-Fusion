package rotp.leader;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Paths in a match file are relative to that file. Credentials stay outside the match and replay. */
public record MatchConfig(String galaxySize, long draftSeed, Map<String, Endpoint> endpoints, List<Seat> seats) {
    public record Endpoint(String baseUrl, String apiKeyEnv) {
        URI chatUri() {
            URI uri = URI.create(requireText(baseUrl, "baseUrl").replaceAll("/+$", "") + "/chat/completions");
            if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Expected an HTTP(S) endpoint baseUrl without credentials or query");
            return uri;
        }

        String apiKey() {
            return apiKeyEnv == null ? null : requireText(System.getenv(apiKeyEnv), "environment variable " + apiKeyEnv);
        }
    }

    public record Seat(String type, String name, String endpoint, String model, String preset,
                       String promptFile, String prompt) {
        public boolean isLeader() { return "llm".equals(type); }

        String loadPrompt(Path directory) throws IOException {
            int sources = (preset == null ? 0 : 1) + (promptFile == null ? 0 : 1) + (prompt == null ? 0 : 1);
            if (sources != 1) throw new IllegalArgumentException("Choose one of preset, promptFile or prompt for " + name);
            if (prompt != null) return requireText(prompt, "prompt");
            if (promptFile != null) return requireText(Files.readString(directory.resolve(promptFile)), "promptFile contents");
            if (!Set.of("shodan", "glados", "skynet", "data", "edi").contains(preset))
                throw new IllegalArgumentException("Unknown persona preset " + preset);
            try (var input = MatchConfig.class.getResourceAsStream("personas/" + preset + ".txt")) {
                if (input == null) throw new IOException("Missing persona resource " + preset);
                return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
        }
    }

    public static MatchConfig load(Path path) throws IOException {
        MatchConfig config;
        try (var reader = Files.newBufferedReader(path)) {
            config = new Gson().fromJson(reader, MatchConfig.class);
        } catch (JsonParseException invalid) {
            throw new IllegalArgumentException("Malformed match JSON in " + path, invalid);
        }
        if (config == null || config.seats == null || config.seats.size() < 2)
            throw new IllegalArgumentException("A match requires at least two seats");
        requireText(config.galaxySize, "galaxySize");
        for (var seat : config.seats) {
            if (seat == null || !("fusion".equals(seat.type) || seat.isLeader()))
                throw new IllegalArgumentException("Expected seat type fusion or llm");
            if (seat.isLeader()) {
                requireText(seat.name, "seat name");
                requireText(seat.model, "model");
                requireText(seat.endpoint, "endpoint");
                if (config.endpoints == null || config.endpoints.get(seat.endpoint) == null)
                    throw new IllegalArgumentException("Unknown endpoint " + seat.endpoint);
                var endpoint = config.endpoints.get(seat.endpoint);
                endpoint.chatUri();
                endpoint.apiKey();
            }
        }
        return config;
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }
}
