package rotp.replay;

import java.io.*;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.nio.file.Path;
import java.util.zip.*;
import rotp.multiplayer.pbem.BuildStamp;

/** Reads only the observer projection; never deserializes game objects. */
public final class ReplayArchive implements Closeable {
    private final ZipFile zip;
    private final int[] turns;
    private final List<ReplayRoster.Seat> roster;

    public ReplayArchive(Path path) throws IOException {
        zip = new ZipFile(path.toFile());
        try (var in = entry("index")) {
            if (!"ROTP Replay 1".equals(in.readUTF())) throw new IOException("Unsupported replay format");
            String build = in.readUTF();
            if (!BuildStamp.current().equals(build)) throw new IOException("Replay requires build " + build);
            int n = ReplayFrame.count(in);
            if (n == 0 || n > zip.size() / 2) throw new IOException("Incomplete replay index");
            turns = new int[n];
            for (int i = 0; i < n; i++) {
                turns[i] = in.readInt();
                if (zip.getEntry("view/" + i) == null || zip.getEntry("state/" + i) == null)
                    throw new IOException("Missing replay frame " + i);
            }
            roster = readRoster();
        } catch (IOException | RuntimeException failure) {
            zip.close();
            throw failure;
        }
    }

    private DataInputStream entry(String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IOException("Missing replay entry: " + name);
        return new DataInputStream(new BufferedInputStream(zip.getInputStream(e)));
    }

    private List<ReplayRoster.Seat> readRoster() throws IOException {
        var entry = zip.getEntry("roster.json");
        if (entry == null) return List.of();
        try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
            var seats = new Gson().fromJson(reader, ReplayRoster.Seat[].class);
            if (seats == null) throw new IOException("Empty replay roster");
            return List.of(seats);
        } catch (JsonParseException | NullPointerException invalid) {
            throw new IOException("Invalid replay roster", invalid);
        }
    }

    public List<ReplayRoster.Seat> roster() { return roster; }

    public int turn(int index) { return turns[index]; }
    public int size() { return turns.length; }
    public ReplayFrame frame(int i) throws IOException {
        if (i < 0 || i >= size()) throw new IOException("Invalid frame " + i);
        try (var in = entry("view/" + i)) {
            var frame = ReplayFrame.read(in);
            if (frame.turn() != turns[i]) throw new IOException("Incorrect turn at frame " + i);
            return frame;
        }
    }
    @Override public void close() throws IOException { zip.close(); }
}
