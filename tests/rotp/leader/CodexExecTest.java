package rotp.leader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class CodexExecTest {
    @TempDir Path directory;

    @Test void sendsPrivateBriefingsAndReadsOnlyTheFinalMessage() throws Exception {
        var executable = directory.resolve("test codex");
        Files.writeString(executable, """
                #!/bin/sh
                set -eu
                test "$1" = exec
                while [ "$#" -gt 0 ]; do
                    case "$1" in
                        --output-schema) shift; schema=$1 ;;
                        --output-last-message) shift; reply=$1 ;;
                        --model) shift; test "$1" = fixture-model ;;
                        -c) shift; if [ "$1" = 'model_reasoning_effort="high"' ]; then high=yes; fi ;;
                        --ephemeral) ephemeral=yes ;;
                    esac
                    shift
                done
                test "$ephemeral" = yes
                test "$high" = yes
                input=$(cat)
                case "$input" in *"persona for seat A"*"only visible to A"*) ;; *) exit 3 ;; esac
                echo 'CLI progress is not a decision'
                echo '{"type":"turn.completed","usage":{"input_tokens":42,"output_tokens":7}}'
                if grep -q preferences "$schema"; then
                    echo '{"summary":"Research","preferences":["RACE_PSILON"]}' > "$reply"
                else
                    echo '{"summary":"Explore","orders":[],"allocation":null,"diplomacy":[]}' > "$reply"
                fi
                """);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        var client = new CodexExec(executable.toString());
        var seat = new MatchConfig.Seat("codex", "A", null, "fixture-model", "data", null, null, "high");
        assertTrue(seat.modelLabel().contains("reasoning high"));
        var draft = client.ask(seat, "persona for seat A", LeaderClient.Request.DRAFT, "only visible to A");
        assertEquals("RACE_PSILON", draft.getAsJsonArray("preferences").get(0).getAsString());
        var usage = new MatchMetrics.Usage();
        var turn = client.ask(seat, "persona for seat A", LeaderClient.Request.TURN, "only visible to A", usage);
        assertEquals(42L, usage.input);
        assertEquals(7L, usage.output);
        assertEquals("Explore", turn.get("summary").getAsString());
        assertFalse(turn.has("allocation"));
    }

    @Test void reportsMissingExecutableWithoutExposingBriefing() {
        var client = new CodexExec(directory.resolve("missing").toString());
        var seat = new MatchConfig.Seat("codex", "A", null, null, "data", null, null);
        var failure = assertThrows(CodexExec.Failure.class,
                () -> client.ask(seat, "private persona", LeaderClient.Request.TURN, "private order"));
        assertTrue(failure.getMessage().contains("PATH"));
        assertFalse(failure.getMessage().contains("private"));
    }
}
