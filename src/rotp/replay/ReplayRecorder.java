package rotp.replay;

import java.io.*;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import rotp.leader.MatchConfig;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import rotp.model.ai.interfaces.Diplomat;
import rotp.model.empires.Empire;
import rotp.model.game.GameSession;
import rotp.multiplayer.pbem.BuildStamp;

/** Bound to the synchronous headless turn driver for the duration of one match. */
public final class ReplayRecorder implements Closeable {
    private static final ThreadLocal<ReplayRecorder> active = new ThreadLocal<>();
    private final Path target, temporary;
    private final ZipOutputStream zip;
    private final List<Integer> turns = new ArrayList<>();
    private final List<ReplayFrame.Event> journal = new ArrayList<>();
    private final GameSession session;
    private final MatchConfig match;
    private final String[] ais;
    private boolean complete;

    public ReplayRecorder(Path path, GameSession game) throws IOException {
        this(path, game, null, null);
    }

    public ReplayRecorder(Path path, GameSession game, MatchConfig match, String[] ais) throws IOException {
        this.match = match;
        this.ais = ais == null ? null : ais.clone();
        target = path.toAbsolutePath();
        if (Files.isDirectory(target)) throw new IOException("Recording path is a directory: " + target);
        session = game;
        temporary = Files.createTempFile(target.getParent(), ".rotp-replay-", ".tmp");
        zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)));
        zip.setLevel(Deflater.BEST_SPEED);
        active.set(this);
    }

    private final Map<Diplomat, Diplomat> diplomats = new IdentityHashMap<>();

    public static Diplomat diplomat(Empire empire, Diplomat delegate) {
        var recorder = active.get();
        return recorder == null ? delegate : recorder.diplomats.computeIfAbsent(delegate,
                d -> ReplayDiplomacy.observe(empire, d));
    }

    public static boolean recording() { return active.get() != null; }
    public static void event(String kind, int empire, int system, String details) {
        var recorder = active.get();
        if (recorder == null) return;
        var game = recorder.session;
        var phase = game.turnCoordinator().activePhase();
        recorder.journal.add(new ReplayFrame.Event(game.galaxy().currentTurn(),
                phase == null ? "Turn boundary" : phase.name(), kind, empire, system, details));
    }

    public void capture() throws IOException {
        int i = turns.size();
        try {
            zip.putNextEntry(new ZipEntry("state/" + i));
            session.writeSnapshot(zip);
            zip.closeEntry();
            var frame = ReplayFrame.capture(session, journal);
            zip.putNextEntry(new ZipEntry("view/" + i));
            frame.write(new DataOutputStream(zip));
            zip.closeEntry();
            turns.add(frame.turn());
            journal.clear();
        } catch (IOException failure) {
            throw new IOException("Recording " + target + " at turn " + session.galaxy().currentTurn(), failure);
        }
    }

    public void finish() throws IOException {
        zip.putNextEntry(new ZipEntry("roster.json"));
        zip.write(new Gson().toJson(ReplayRoster.capture(session, match, ais)).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("index"));
        var out = new DataOutputStream(zip);
        out.writeUTF("ROTP Replay 1");
        out.writeUTF(BuildStamp.current());
        out.writeInt(turns.size());
        for (int turn : turns) out.writeInt(turn);
        zip.closeEntry();
        zip.close();
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        complete = true;
    }

    @Override public void close() throws IOException {
        active.remove();
        try { zip.close(); }
        finally { if (!complete) Files.deleteIfExists(temporary); }
    }
}
