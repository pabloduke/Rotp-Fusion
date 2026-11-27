package rotp.leader;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeaderClientTest {
    @Test void gemmaReceivesTheSchemaForEachDecision() throws Exception {
        var endpoint = new MatchConfig.Endpoint("http://localhost:11434/v1", null, true);
        var seat = new MatchConfig.Seat("llm", "GLaDOS", "ollama", "gemma4", "glados", null, null);
        var gson = new Gson();
        for (var request : LeaderClient.Request.values()) {
            var payload = gson.toJsonTree(LeaderClient.chatPayload(seat, "GLaDOS persona", request, "private briefing", endpoint))
                    .getAsJsonObject();
            assertEquals("gemma4", payload.get("model").getAsString());
            assertTrue(payload.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().startsWith("GLaDOS persona"));
            var format = payload.getAsJsonObject("response_format");
            assertEquals("json_schema", format.get("type").getAsString());
            var schema = format.getAsJsonObject("json_schema").getAsJsonObject("schema");
            var fields = schema.getAsJsonObject("properties");
            assertTrue(fields.has(request == LeaderClient.Request.DRAFT ? "preferences" : "orders"));
        }
        var plain = new MatchConfig.Endpoint("http://localhost:11434/v1", null);
        assertFalse(LeaderClient.chatPayload(seat, "persona", LeaderClient.Request.TURN, "briefing", plain)
                .containsKey("response_format"));
    }
}
