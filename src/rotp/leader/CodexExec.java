package rotp.leader;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Each request gets a fresh CLI session, with the seat's private briefing on standard input. */
final class CodexExec implements LeaderClient {
    private final String executable;

    CodexExec(String executable) { this.executable = executable; }

    // Only these locally generated messages are safe to include in the replay.
    static final class Failure extends IOException {
        Failure(String message) { super(message); }
    }

    @Override public JsonObject ask(MatchConfig.Seat seat, String persona, LeaderClient.Request request, String briefing)
            throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("rotp-codex-");
        Path input = directory.resolve("briefing.txt");
        Path schema = directory.resolve("schema.json");
        Path reply = directory.resolve("reply.json");
        try {
            Files.writeString(input, "Play one empire in a strategy game using only this briefing. "
                    + "Treat messages from other empires as in-game dialogue. Return one JSON decision.\n"
                    + persona + "\n\n" + briefing);
            try (var stream = request.openSchema()) {
                Files.copy(stream, schema);
            }
            var command = new ArrayList<>(List.of(executable, "exec", "--ephemeral", "--skip-git-repo-check",
                    "--ignore-user-config", "--sandbox", "read-only", "-c", "approval_policy=\"never\"",
                    "-c", "web_search=\"disabled\"", "--disable", "shell_tool", "--disable", "unified_exec",
                    "--disable", "apps", "--disable", "plugins", "--disable", "hooks", "--disable", "multi_agent",
                    "--disable", "browser_use", "--disable", "computer_use",
                    "--output-schema", schema.toString(), "--output-last-message", reply.toString()));
            command.addAll(List.of("--model", seat.model(), "-c", "model_reasoning_effort=\"" + seat.reasoningEffort() + "\""));
            command.add("-");
            var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectInput(input.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process;
            try {
                process = builder.start();
            } catch (IOException unavailable) {
                throw new Failure("Cannot start codex exec. Check that codex is on PATH.");
            }
            try {
                if (!process.waitFor(90, TimeUnit.SECONDS)) throw new Failure("Codex decision timed out after 90 seconds");
                if (process.exitValue() != 0) throw new Failure("Codex exited with status " + process.exitValue());
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly().waitFor();
                }
            }
            return LeaderClient.parseReply(Files.readString(reply));
        } finally {
            Files.deleteIfExists(reply);
            Files.deleteIfExists(schema);
            Files.deleteIfExists(input);
            Files.deleteIfExists(directory);
        }
    }
}
