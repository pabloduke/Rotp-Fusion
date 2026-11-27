package rotp.replay;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.file.*;

public final class ReplayVideo {
    private ReplayVideo() { }

    public static Path export(Path recording) throws IOException {
        String name = recording.getFileName().toString();
        int suffix = name.lastIndexOf('.');
        var target = recording.resolveSibling((suffix > 0 ? name.substring(0,suffix) : name) + ".mp4").toAbsolutePath();
        if (Files.exists(target) && Files.isSameFile(recording,target))
            throw new IOException("Video destination conflicts with recording: " + recording);
        try (var archive = new ReplayArchive(recording)) {
            Path temporary = Files.createTempFile(target.getParent(), ".territory-", ".mp4");
            try {
                encode(archive,temporary);
                try {
                    Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
                }
                return target;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void encode(ReplayArchive archive, Path destination) throws IOException {
        var image = new BufferedImage(1920,1080,BufferedImage.TYPE_3BYTE_BGR);
        var bytes = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
        var map = new ReplayMap(archive.frame(0));
        var process = new ProcessBuilder("ffmpeg","-hide_banner","-loglevel","error","-y","-f","rawvideo",
                "-pixel_format","bgr24","-video_size","1920x1080","-framerate","10","-i","pipe:0",
                "-c:v","libx264","-threads","4","-preset","veryfast","-pix_fmt","yuv420p","-movflags","+faststart",
                destination.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var graphics = image.createGraphics();
        try {
            try (var input = process.getOutputStream()) {
                for (int i=0; i<archive.size(); i++) {
                    if (i+1 < archive.size() && archive.turn(i) == archive.turn(i+1)) continue;
                    map.draw(graphics,image.getWidth(),image.getHeight(),archive.frame(i),true,null);
                    input.write(bytes);
                }
            }
            if (process.waitFor() != 0) throw new IOException("FFmpeg failed with exit status " + process.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Video export interrupted",e);
        } finally {
            graphics.dispose();
            process.destroyForcibly();
        }
    }
}
