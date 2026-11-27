package rotp.replay;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.nio.file.*;
import java.util.List;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rotp.multiplayer.pbem.BuildStamp;
import static org.junit.jupiter.api.Assertions.*;

class ReplayVideoTest {
    @TempDir Path directory;

    private static ReplayFrame frame(int turn, int owner) {
        var star = new ReplayFrame.Item("System", "A", owner,
                (owner == 1 ? Color.RED : Color.BLUE).getRGB(), 4,4,"");
        var neighbor = new ReplayFrame.Item("System", "B", 0, Color.BLUE.getRGB(),8,4,"");
        return new ReplayFrame(turn,"",List.of(star,neighbor),List.of());
    }

    private static ReplayFrame withEvents(ReplayFrame frame, ReplayFrame.Event... events) {
        return new ReplayFrame(frame.turn(),frame.outcome(),frame.items(),List.of(events));
    }

    private static ReplayFrame.Event event(String kind, int turn, int system) {
        return new ReplayFrame.Event(turn,"",kind,0,system,"");
    }

    @Test void conflictHistoryUsesGameTurnsAndIsIndependentOfSeekOrder() {
        var history = new ReplayConflicts();
        history.observe(frame(1,-1));
        history.observe(frame(2,0)); // First settlement
        history.observe(withEvents(frame(3,0),event("Battle",3,0),event("Bombardment",3,1)));
        history.observe(frame(3,1)); // Capture takes precedence at system 0
        history.observe(withEvents(frame(5,1),event("Invasion",5,1)));
        assertTrue(history.at(2).isEmpty());
        assertEquals(new ReplayConflicts.Cue(4,true),history.at(7).get(0));
        assertEquals(new ReplayConflicts.Cue(2,false),history.at(7).get(1));
        assertFalse(history.at(8).containsKey(0));
        assertTrue(history.at(10).isEmpty());
        assertEquals(new ReplayConflicts.Cue(0,true),history.at(3).get(0));
        assertEquals(new ReplayConflicts.Cue(0,false),history.at(3).get(1));
    }

    @Test void exportsOneFinalOwnershipMapPerTurn() throws Exception {
        Path path = directory.resolve("fixture.rotpreplay");
        var terminal = new ReplayFrame(4,"MILITARY_ALLIANCE — Alpha, Beta",frame(4,-1).items(),List.of());
        assertEquals("Military alliance victory — Won by Alpha, Beta",terminal.result().summary());
        assertNull(frame(1,-1).result());
        var frames = List.of(frame(1,-1), frame(2,0), withEvents(frame(3,0),event("Battle",3,1)), frame(3,1), terminal);
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            var out = new DataOutputStream(zip);
            for (int i=0; i<frames.size(); i++) {
                zip.putNextEntry(new ZipEntry("state/"+i));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("view/"+i));
                frames.get(i).write(out);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("index"));
            out.writeUTF("ROTP Replay 1");
            out.writeUTF(BuildStamp.current());
            out.writeInt(frames.size());
            for (var f : frames) out.writeInt(f.turn());
        }
        var video = ReplayVideo.export(path);
        var probe = new ProcessBuilder("ffprobe","-v","error","-count_frames","-show_entries",
                "stream=codec_name,width,height,pix_fmt,r_frame_rate,nb_read_frames:format_tags=comment","-of","default=nw=1",video.toString()).start();
        String metadata = new String(probe.getInputStream().readAllBytes());
        assertEquals(0,probe.waitFor());
        for (var property : List.of("codec_name=h264","width=1920","height=1080","pix_fmt=yuv420p","r_frame_rate=10/1","nb_read_frames=4","Military alliance victory — Won by Alpha, Beta"))
            assertTrue(metadata.contains(property),metadata);
        ReplayMap map;
        try (var archive = new ReplayArchive(path)) { map = ReplayMap.from(archive); }
        var point = map.point(frames.get(0).items().get(0),1920,1080,true);
        point.translate(30,30);
        var decode = new ProcessBuilder("ffmpeg","-loglevel","error","-i",video.toString(),"-vf",
                "format=rgb24,crop=1:1:"+point.x+":"+point.y,"-threads","1","-f","rawvideo","pipe:1").start();
        byte[] samples = decode.getInputStream().readAllBytes();
        assertEquals(0,decode.waitFor());
        assertEquals(12,samples.length);
        var ring = map.point(frames.get(0).items().get(0),1920,1080,true);
        ring.translate(17,0);
        var ringDecode = new ProcessBuilder("ffmpeg","-loglevel","error","-i",video.toString(),"-vf",
                "format=rgb24,crop=1:1:"+ring.x+":"+ring.y,"-threads","1","-f","rawvideo","pipe:1").start();
        byte[] ringPixels = ringDecode.getInputStream().readAllBytes();
        assertEquals(0,ringDecode.waitFor());
        assertEquals(12,ringPixels.length);
        assertTrue(Byte.toUnsignedInt(ringPixels[7]) > 145, "Capture has a bright gold outer ring");
        assertTrue(Byte.toUnsignedInt(ringPixels[10]) < Byte.toUnsignedInt(ringPixels[7]), "Outer ring fades");
        int offset = 0;
        for (var source : List.of(frames.get(0),frames.get(1),frames.get(3),frames.get(4))) {
            var preview = new BufferedImage(1920,1080,BufferedImage.TYPE_INT_RGB);
            var graphics = preview.createGraphics();
            try { map.draw(graphics,1920,1080,source,true,null); }
            finally { graphics.dispose(); }
            var color = new Color(preview.getRGB(point.x,point.y));
            if (source.turn() == 2) assertEquals(new Color(11,13,141),color);
            if (source.turn() == 3) assertEquals(new Color(136,13,16),color);
            for (int channel : List.of(color.getRed(),color.getGreen(),color.getBlue()))
                assertEquals(channel,Byte.toUnsignedInt(samples[offset++]),5);
        }
    }
}
