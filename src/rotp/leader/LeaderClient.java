package rotp.leader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@FunctionalInterface
interface LeaderClient {
    /** A game-rule validation message that can be returned to the leader on its one retry. */
    final class InvalidDecision extends IllegalArgumentException {
        InvalidDecision(String reason) { super(reason); }
    }

    enum Request {
        DRAFT("draft-schema.json"), TURN("turn-schema.json");

        private final String schemaFile;
        Request(String schemaFile) { this.schemaFile = schemaFile; }

        InputStream openSchema() throws IOException {
            var input = LeaderClient.class.getResourceAsStream(schemaFile);
            if (input == null) throw new IOException("Missing leader schema " + schemaFile);
            return input;
        }
    }

    static JsonObject parseReply(String content) {
        var decision = JsonParser.parseString(content).getAsJsonObject();
        // A structured response uses null to retain the current research policy.
        if (decision.has("allocation") && decision.get("allocation").isJsonNull()) decision.remove("allocation");
        return decision;
    }

    JsonObject ask(MatchConfig.Seat seat, String persona, Request request, String briefing) throws IOException, InterruptedException;

    static Map<String, Object> chatPayload(MatchConfig.Seat seat, String persona, Request request,
                                           String briefing, MatchConfig.Endpoint endpoint) throws IOException {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", seat.model());
        if (endpoint.structuredOutput()) {
            try (var reader = new InputStreamReader(request.openSchema(), StandardCharsets.UTF_8)) {
                var schema = JsonParser.parseReader(reader).getAsJsonObject();
                payload.put("response_format", Map.of("type", "json_schema", "json_schema",
                        Map.of("name", request.name(), "strict", true, "schema", schema)));
            }
            if (request == Request.TURN)
                briefing += "\nUse null to retain the allocation. Copy order and diplomacy choices verbatim. "
                        + "Use empty arrays when you have no orders or diplomatic actions.";
        }
        payload.put("messages", List.of(
                Map.of("role", "system", "content", persona + "\nPlay one empire in a strategy game. Return only the requested JSON object."),
                Map.of("role", "user", "content", briefing)));
        return payload;
    }

    static LeaderClient forMatch(MatchConfig config) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var gson = new Gson();
        var codex = new CodexExec("codex");
        return (seat, persona, request, briefing) -> {
            if (seat.isCodex()) return codex.ask(seat, persona, request, briefing);
            var endpoint = config.endpoints().get(seat.endpoint());
            var builder = HttpRequest.newBuilder(endpoint.chatUri()).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(chatPayload(seat, persona, request, briefing, endpoint))));
            if (endpoint.apiKeyEnv() != null) builder.header("Authorization", "Bearer " + endpoint.apiKey());
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
            var envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = envelope.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            return parseReply(content);
        };
    }
}
