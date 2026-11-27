package rotp.leader;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MatchConfigTest {
    @Test void resolvesCodexDefaultsIndependentlyWhenLoadingSeats(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("match.json");
        Files.writeString(file, """
                {"galaxySize":"small","seats":[
                  {"type":"codex","name":"A","preset":"data"},
                  {"type":"codex","name":"B","preset":"edi","model":"gpt-6-astra"},
                  {"type":"codex","name":"C","preset":"shodan","reasoningEffort":"high"},
                  {"type":"fusion"}
                ]}
                """);
        var seats = MatchConfig.load(file).seats();
        assertEquals("Codex CLI, gpt-5.6-terra, reasoning low", seats.get(0).modelLabel());
        assertEquals("Codex CLI, gpt-6-astra, reasoning low", seats.get(1).modelLabel());
        assertEquals("Codex CLI, gpt-5.6-terra, reasoning high", seats.get(2).modelLabel());
        assertNull(seats.get(3).model());
        assertNull(seats.get(3).reasoningEffort());
    }
}
