package rotp.replay;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Read-only catalog. Only archive basenames discovered in the configured directory receive IDs. */
final class ReplayLibrary implements Closeable {
    private static final String EXTENSION = ".rotpreplay";
    private static final Map<String,String> COMPANIONS = Map.of(
            "video", ".mp4", "journal", "-journal.txt", "diplomacy", "-diplomacy.txt");
    private static final Pattern MAIL = Pattern.compile("^To #(\\d+): ");
    private final Path directory;
    private final Map<String, Entry> catalog = new LinkedHashMap<>();
    private final Map<String, Opened> opened = new LinkedHashMap<>(4, .75f, true);

    record Empire(int id, String name, String color) {}
    record Summary(String id, String name, int turns, int frames, List<Empire> empires, String error) {}
    record Metadata(String id, String name, int[] turns, List<Empire> empires, List<String> downloads) {}
    record Event(int frame, int turn, String phase, String kind, int empire, int system,
                 String details, Integer recipient, String category) {
        Event(int index, ReplayFrame.Event event) {
            this(index, event.turn(), event.phase(), event.kind(), event.empire(), event.system(), event.details(),
                    recipientOf(event), categoryOf(event.kind()));
        }

        private static Integer recipientOf(ReplayFrame.Event event) {
            if (!event.kind().equals("Diplomatic mail")) return null;
            var match = MAIL.matcher(event.details());
            if (!match.find()) return null;
            try { return Integer.valueOf(match.group(1)); }
            catch (NumberFormatException ignored) { return null; }
        }

        private static String categoryOf(String kind) {
            return switch (kind) {
                case "Diplomatic mail", "Diplomacy", "Reply" -> "diplomacy";
                case "Battle", "Invasion", "Bombardment" -> "battles";
                case "Race draft" -> "journal";
                default -> kind.startsWith("Leader ") ? "journal" : "other";
            };
        }
    }
    record Page(int total, int offset, List<Event> events) {}
    record Download(Path path, String contentType) {}
    private record Stamp(long size, FileTime modified, Object fileKey) {}
    private record Entry(Path file, Stamp stamp, Summary summary) {}

    ReplayLibrary(Path directory) throws IOException {
        this.directory = directory.toRealPath();
        if (!Files.isDirectory(this.directory)) throw new IOException("Expected a replay directory");
    }

    private static Stamp stamp(Path file) throws IOException {
        var attr = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attr.isRegularFile()) throw new NoSuchFileException("Missing replay");
        return new Stamp(attr.size(), attr.lastModifiedTime(), attr.fileKey());
    }

    synchronized List<Summary> list() throws IOException {
        var present = new HashSet<String>();
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) continue;
                String id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
                try {
                    refresh(id, file);
                    present.add(id);
                } catch (NoSuchFileException ignored) { /* File removed during refresh or not regular. */ }
            }
        }
        for (String id : new ArrayList<>(catalog.keySet())) {
            if (!present.contains(id)) {
                evict(id);
                catalog.remove(id);
            }
        }
        return catalog.values().stream().map(Entry::summary)
                .sorted(Comparator.comparing(Summary::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private void refresh(String id, Path file) throws IOException {
        var version = stamp(file);
        var old = catalog.get(id);
        if (old != null && old.stamp.equals(version)) return;
        evict(id);
        String name = file.getFileName().toString();
        name = name.substring(0, name.length()-EXTENSION.length());
        Summary summary;
        try (var archive = new ReplayArchive(file)) {
            var empires = archive.frame(0).items().stream().filter(i -> i.kind().equals("Empire"))
                    .map(i -> new Empire(i.empire(), i.name(), String.format("#%06x", i.color() & 0xffffff))).toList();
            summary = new Summary(id, name, archive.turn(archive.size()-1), archive.size(), empires, null);
        } catch (IOException | RuntimeException failure) {
            summary = new Summary(id, name, 0, 0, List.of(), archiveError(failure));
        }
        catalog.put(id, new Entry(file, version, summary));
    }

    static String archiveError(Exception failure) {
        String message = failure.getMessage();
        if (message != null && message.startsWith("Replay requires build ")) return message;
        return "Unreadable or incomplete replay";
    }

    private Entry find(String id) throws IOException {
        var entry = catalog.get(id);
        if (entry == null) {
            list();
            entry = catalog.get(id);
            if (entry == null) throw new NoSuchFileException("Unknown replay");
        } else refresh(id, entry.file);
        entry = catalog.get(id);
        if (entry.summary.error != null) throw new IOException(entry.summary.error);
        return entry;
    }

    private Opened open(String id) throws IOException {
        var entry = find(id);
        var result = opened.get(id);
        if (result == null) {
            if (opened.size() >= 2) evict(opened.keySet().iterator().next());
            result = new Opened(entry.file);
            opened.put(id, result);
        }
        return result;
    }

    private void evict(String id) throws IOException {
        var previous = opened.remove(id);
        if (previous != null) previous.archive.close();
    }

    synchronized Metadata metadata(String id) throws IOException {
        var data = open(id);
        var entry = catalog.get(id);
        var downloads = COMPANIONS.keySet().stream().sorted().filter(kind -> Files.isRegularFile(
                companion(entry, kind), LinkOption.NOFOLLOW_LINKS)).toList();
        return new Metadata(id, entry.summary.name, data.turns, entry.summary.empires, downloads);
    }

    private static Path companion(Entry entry, String kind) {
        return entry.file.resolveSibling(entry.summary.name + COMPANIONS.get(kind));
    }

    synchronized Download download(String id, String kind) throws IOException {
        if (!COMPANIONS.containsKey(kind)) throw new NoSuchFileException("Unknown download");
        Path file = companion(find(id), kind);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("Missing download");
        return new Download(file, kind.equals("video") ? "video/mp4" : "text/plain; charset=utf-8");
    }

    synchronized ReplayFrame.Result outcome(String id) throws IOException { return open(id).result; }

    synchronized Page events(String id, int maxFrame, String category, Integer empire, Integer offset, int limit) throws IOException {
        var replay = open(id);
        replay.checkFrame(maxFrame);
        if (!Set.of("all", "journal", "diplomacy", "battles").contains(category))
            throw new IllegalArgumentException("Unknown event category");
        var matches = replay.events.stream().takeWhile(e -> e.frame <= maxFrame)
                .filter(e -> category.equals("all") || category.equals(e.category))
                .filter(e -> empire == null || empire == e.empire || empire.equals(e.recipient)).toList();
        int from = offset == null ? Math.max(0,(matches.size()-1)/limit*limit) : Math.min(offset, matches.size());
        return new Page(matches.size(), from, matches.subList(from, from + Math.min(limit, matches.size()-from)));
    }

    synchronized byte[] map(String id, int frame) throws IOException { return open(id).image(frame); }

    @Override public synchronized void close() throws IOException {
        for (String id : new ArrayList<>(opened.keySet())) evict(id);
    }

    /** The archive and its PNG cache have the same lifetime, and are accessed under the library lock. */
    private static final class Opened {
        final ReplayArchive archive;
        final List<Event> events = new ArrayList<>();
        final ReplayMap map;
        final ReplayFrame.Result result;
        final int[] turns;
        final Map<Integer, byte[]> images = new LinkedHashMap<>(12, .75f, true);

        Opened(Path file) throws IOException {
            archive = new ReplayArchive(file);
            try {
                turns = new int[archive.size()];
                var conflicts = new ReplayConflicts();
                var initial = archive.frame(0);
                var current = initial;
                for (int i = 0; i < turns.length; i++) {
                    if (i > 0) current = archive.frame(i);
                    turns[i] = current.turn();
                    conflicts.observe(current);
                    for (var event : current.events()) events.add(new Event(i,event));
                }
                result = current.result();
                map = new ReplayMap(initial, conflicts);
            } catch (IOException | RuntimeException failure) {
                archive.close();
                throw new IOException(archiveError(failure), failure);
            }
        }

        void checkFrame(int index) {
            if (index < 0 || index >= turns.length) throw new IllegalArgumentException("Invalid replay frame");
        }

        byte[] image(int frame) throws IOException {
            checkFrame(frame);
            byte[] image = images.get(frame);
            if (image == null) {
                var bitmap = new BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB);
                var graphics = bitmap.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    map.draw(graphics,1280,720,archive.frame(frame),true,null,frame);
                } finally { graphics.dispose(); }
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(bitmap, "png", bytes);
                image = bytes.toByteArray();
                if (images.size() >= 8) images.remove(images.keySet().iterator().next());
                images.put(frame, image);
            }
            return image;
        }
    }
}
