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

    @Test void exportsOneFinalOwnershipMapPerTurn() throws Exception {
        Path path = directory.resolve("fixture.rotpreplay");
        var frames = List.of(frame(1,-1), frame(2,0), frame(3,0), frame(3,1), frame(4,-1));
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
                "stream=codec_name,width,height,pix_fmt,r_frame_rate,nb_read_frames","-of","default=nw=1",video.toString()).start();
        String metadata = new String(probe.getInputStream().readAllBytes());
        assertEquals(0,probe.waitFor());
        for (var property : List.of("codec_name=h264","width=1920","height=1080","pix_fmt=yuv420p","r_frame_rate=10/1","nb_read_frames=4"))
            assertTrue(metadata.contains(property),metadata);
        var map = new ReplayMap(frames.get(0));
        var point = map.point(frames.get(0).items().get(0),1920,1080,true);
        point.translate(10,10);
        var decode = new ProcessBuilder("ffmpeg","-loglevel","error","-i",video.toString(),"-vf",
                "format=rgb24,crop=1:1:"+point.x+":"+point.y,"-threads","1","-f","rawvideo","pipe:1").start();
        byte[] samples = decode.getInputStream().readAllBytes();
        assertEquals(0,decode.waitFor());
        assertEquals(12,samples.length);
        int offset = 0;
        for (var source : List.of(frames.get(0),frames.get(1),frames.get(3),frames.get(4))) {
            var preview = new BufferedImage(1920,1080,BufferedImage.TYPE_INT_RGB);
            var graphics = preview.createGraphics();
            try { map.draw(graphics,1920,1080,source,true,null); }
            finally { graphics.dispose(); }
            var color = new Color(preview.getRGB(point.x,point.y));
            if (source.turn() == 2) assertEquals(Color.BLUE.darker(),color);
            if (source.turn() == 3) assertEquals(Color.RED.darker(),color);
            for (int channel : List.of(color.getRed(),color.getGreen(),color.getBlue()))
                assertEquals(channel,Byte.toUnsignedInt(samples[offset++]),5);
        }
    }
}
