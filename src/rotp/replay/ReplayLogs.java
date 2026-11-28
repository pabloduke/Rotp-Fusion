package rotp.replay;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Exports observer-facing text from recorded events in their original order. */
public final class ReplayLogs {
    private ReplayLogs() { }

    public record FilesWritten(Path diplomacy, Path journal) {
        @Override public String toString() {
            return "Diplomacy: " + diplomacy + System.lineSeparator() + "Journal: " + journal;
        }
    }

    private static final Pattern ADDRESS = Pattern.compile("^To #([0-9]{1,9}): ");

    public static FilesWritten export(Path recording) throws IOException {
        var source = recording.toAbsolutePath();
        var name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        var stem = dot > 0 ? name.substring(0, dot) : name;
        var result = new FilesWritten(source.resolveSibling(stem + "-diplomacy.txt"),
                source.resolveSibling(stem + "-journal.txt"));

        try (var archive = new ReplayArchive(source)) {
            Path diplomacy = Files.createTempFile(source.getParent(), ".diplomacy-", ".tmp");
            try {
                Path journal = Files.createTempFile(source.getParent(), ".journal-", ".tmp");
                try {
                    try (var mail = Files.newBufferedWriter(diplomacy); var log = Files.newBufferedWriter(journal)) {
                        write(archive, mail, log);
                    }
                    publish(diplomacy, result.diplomacy);
                    publish(journal, result.journal);
                } finally {
                    Files.deleteIfExists(journal);
                }
            } finally {
                Files.deleteIfExists(diplomacy);
            }
        }
        return result;
    }

    private static void publish(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<Integer, String> participants(ReplayFrame firstFrame) {
        var names = new HashMap<Integer, String>();
        for (var item : firstFrame.items())
            if (item.kind().equals("Empire")) names.put(item.empire(), item.name() + " (#" + item.empire() + ")");
        return names;
    }

    private static void write(ReplayArchive archive, BufferedWriter mail, BufferedWriter journal) throws IOException {
        var frame = archive.frame(0);
        var names = participants(frame);
        boolean hasDiplomacy = false, hasJournal = false;
        mail.write("Diplomatic transcript\n");
        journal.write("Strategic journal\n");

        for (int i = 0; i < archive.size(); i++) {
            if (i > 0) frame = archive.frame(i);
            for (var event : frame.events()) {
                String actor = names.getOrDefault(event.empire(), "Empire #" + event.empire());
                String heading = "\nTurn " + event.turn() + " | " + actor;
                String details = event.details();
                switch (event.kind()) {
                    case "Diplomatic mail" -> {
                        var address = ADDRESS.matcher(details);
                        if (address.find()) {
                            int id = Integer.parseInt(address.group(1));
                            heading += " to " + names.getOrDefault(id, "Empire #" + id);
                            details = details.substring(address.end());
                        }
                        mail.write(heading + "\n" + details + "\n");
                        hasDiplomacy = true;
                    }
                    case "Diplomacy", "Reply", "Treaty" -> {
                        mail.write(heading + " | " + event.kind() + "\n" + details + "\n");
                        hasDiplomacy = true;
                    }
                    case "Leader strategy", "Race draft", "Leader order", "Leader objective", "Leader request failed", "Leader fallback" -> {
                        String kind = event.kind();
                        // Previously the engine recorded this fallback as a strategy event.
                        if (kind.equals("Leader strategy") && details.equals("No valid response. Retaining existing orders."))
                            kind = "Leader fallback";
                        journal.write(heading + " | " + kind + "\n" + details + "\n");
                        hasJournal = true;
                    }
                    default -> { }
                }
            }
        }
        if (!hasDiplomacy) mail.write("\nNo diplomatic exchanges were recorded.\n");
        if (!hasJournal) journal.write("\nNo leader journal entries were recorded.\n");
    }
}
