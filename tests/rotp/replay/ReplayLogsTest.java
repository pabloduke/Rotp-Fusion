package rotp.replay;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rotp.multiplayer.pbem.BuildStamp;

import static org.junit.jupiter.api.Assertions.*;

class ReplayLogsTest {
    @TempDir Path directory;

    @Test void exportsMailAndJournalInOrderFromOnlyObserverFrames() throws IOException {
        var empires = List.of(new ReplayFrame.Item("Empire", "Laboratories", 3, -1, 0, 0, ""),
                new ReplayFrame.Item("Empire", "Republic", 7, -1, 0, 0, ""));
        var opening = new ReplayFrame(1, "", empires, List.of(
                event(1, 3, "Leader strategy", "Study the Republic."),
                event(1, 3, "Leader order", "Applied scout/1/22"),
                event(1, 3, "Diplomatic mail", "To #7: Offer #1 (pact): Une expérience?\nTerms follow."),
                event(1, 3, "Leader briefing", "private model input")));
        var late = new ReplayFrame(3, "", empires, List.of(
                event(2, 7, "Diplomatic mail", "To #3: refuse offer #1: Declined."),
                event(3, 3, "Leader objective", "Blocked scout/22/1"),
                event(3, 3, "Leader strategy", "No valid response. Retaining existing orders.")));
        var path = archive(opening, late);
        var logs = ReplayLogs.export(path);
        assertEquals(directory.resolve("some.match-diplomacy.txt"), logs.diplomacy());
        String mail = Files.readString(logs.diplomacy());
        assertEquals("""
                Diplomatic transcript

                Turn 1 | Laboratories (#3) to Republic (#7)
                Offer #1 (pact): Une expérience?
                Terms follow.

                Turn 2 | Republic (#7) to Laboratories (#3)
                refuse offer #1: Declined.
                """, mail);
        String journal = Files.readString(logs.journal());
        assertTrue(journal.contains("Turn 1 | Laboratories (#3) | Leader strategy\nStudy the Republic."));
        assertTrue(journal.contains("Leader order\nApplied scout/1/22"));
        assertTrue(journal.contains("Leader objective\nBlocked scout/22/1"));
        assertTrue(journal.contains("Leader fallback\nNo valid response."));
        assertFalse(journal.contains("private model input"));
        assertFalse(journal.contains("Turn 2"));
        ReplayLogs.export(path);
        assertEquals(mail, Files.readString(logs.diplomacy()));
        assertEquals(journal, Files.readString(logs.journal()));
    }

    @Test void quietRecordingProducesReadableEmptyLogs() throws IOException {
        var logs = ReplayLogs.export(archive(new ReplayFrame(1, "", List.of(), List.of())));
        assertTrue(Files.readString(logs.diplomacy()).contains("No diplomatic exchanges were recorded."));
        assertTrue(Files.readString(logs.journal()).contains("No leader journal entries were recorded."));
    }

    private ReplayFrame.Event event(int turn, int empire, String kind, String details) {
        return new ReplayFrame.Event(turn, "Turn boundary", kind, empire, -1, details);
    }

    private Path archive(ReplayFrame... frames) throws IOException {
        var path = directory.resolve("some.match.rotpreplay");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            var out = new DataOutputStream(zip);
            for (int i = 0; i < frames.length; i++) {
                zip.putNextEntry(new ZipEntry("state/" + i));
                zip.putNextEntry(new ZipEntry("view/" + i));
                frames[i].write(out);
            }
            zip.putNextEntry(new ZipEntry("index"));
            out.writeUTF("ROTP Replay 1");
            out.writeUTF(BuildStamp.current());
            out.writeInt(frames.length);
            for (var frame : frames) out.writeInt(frame.turn());
        }
        return path;
    }
}
