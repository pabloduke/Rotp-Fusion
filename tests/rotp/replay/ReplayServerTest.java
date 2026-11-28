package rotp.replay;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import java.util.zip.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rotp.multiplayer.pbem.BuildStamp;

class ReplayServerTest {
    @TempDir Path library;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<byte[]> get(int port, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonElement json(int port, String path) throws Exception {
        var response = get(port, path);
        assertEquals(200, response.statusCode(), path);
        return JsonParser.parseString(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
    }

    // Two frames share turn 1. Only the second contains the diplomatic exchange.
    private void record(Path archive) throws IOException {
        var items = List.of(new ReplayFrame.Item("Empire","Terrans",0,0xff65a8c4,0,0,"Active"),
                new ReplayFrame.Item("System","#0 Sol",0,0xff65a8c4,1,1,"Colonized"),
                new ReplayFrame.Item("System","#1 Tau",-1,0xff888888,8,9,"Uncolonized"));
        var mail = new ReplayFrame.Event(1,"Orders","Diplomatic mail",0,-1,"To #1: <example>");
        var frames = List.of(new ReplayFrame(1,"",items,List.of()),
                new ReplayFrame(1,"",items,List.of(mail)),new ReplayFrame(2,"MILITARY — Terrans",items,List.of()));
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            var out = new DataOutputStream(zip);
            for (int i = 0; i < frames.size(); i++) {
                zip.putNextEntry(new ZipEntry("view/" + i));
                frames.get(i).write(out);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("state/" + i));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("index"));
            out.writeUTF("ROTP Replay 1");
            out.writeUTF(BuildStamp.current());
            out.writeInt(frames.size());
            for (var frame : frames) out.writeInt(frame.turn());
            zip.closeEntry();
        }
    }

    @Test void indexesFramesFiltersEventsAndRestrictsCompanions() throws Exception {
        record(library.resolve("trial.rotpreplay"));
        Files.writeString(library.resolve("trial-journal.txt"),"Trial archive");
        Files.writeString(library.resolve("secret.txt"),"unrelated");
        try (var server = ReplayServer.start(library,"127.0.0.1",0)) {
            int port = server.port();
            var matches = json(port,"/api/replays").getAsJsonArray();
            assertEquals(1,matches.size());
            var summary = matches.get(0).getAsJsonObject();
            assertTrue(summary.get("fileModified").getAsBoolean());
            assertEquals(Files.getLastModifiedTime(library.resolve("trial.rotpreplay")).toMillis(),
                    summary.get("timestampMillis").getAsLong());
            String root = "/api/replays/" + matches.get(0).getAsJsonObject().get("id").getAsString();
            var info = json(port,root).getAsJsonObject();
            assertEquals("[1,1,2]",info.get("turns").toString());
            assertFalse(info.has("winners"));
            assertTrue(info.get("downloads").toString().contains("journal"));
            assertEquals(0,json(port,root + "/events?maxFrame=0").getAsJsonObject().get("total").getAsInt());
            var mail = json(port,root + "/events?maxFrame=1&category=diplomacy&empire=1")
                    .getAsJsonObject().getAsJsonArray("events").get(0).getAsJsonObject();
            assertEquals(1,mail.get("frame").getAsInt());
            assertTrue(mail.get("details").getAsString().contains("<example>"));
            assertTrue(json(port,root + "/outcome").toString().contains("Terrans"));
            assertEquals(200,get(port,root + "/map/1").statusCode());
            assertEquals(200,get(port,root + "/files/journal").statusCode());
            assertTrue(get(port,root + "/files/secret.txt").statusCode() >= 400);
            Files.writeString(library.resolve("broken.rotpreplay"),"broken");
            assertEquals(2,json(port,"/api/replays").getAsJsonArray().size());
        }
    }
}
