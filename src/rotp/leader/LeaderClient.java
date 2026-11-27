package rotp.leader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@FunctionalInterface
interface LeaderClient {
    JsonObject ask(MatchConfig.Seat seat, String persona, String briefing) throws IOException, InterruptedException;

    static LeaderClient http(MatchConfig config) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var gson = new Gson();
        return (seat, persona, briefing) -> {
            var endpoint = config.endpoints().get(seat.endpoint());
            var payload = gson.toJson(Map.of("model", seat.model(), "messages", List.of(
                    Map.of("role", "system", "content", persona + "\nPlay one empire in a strategy game. Return only the requested JSON object."),
                    Map.of("role", "user", "content", briefing))));
            var builder = HttpRequest.newBuilder(endpoint.chatUri()).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (endpoint.apiKeyEnv() != null) builder.header("Authorization", "Bearer " + endpoint.apiKey());
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
            var envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = envelope.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            return JsonParser.parseString(content).getAsJsonObject();
        };
    }
}
