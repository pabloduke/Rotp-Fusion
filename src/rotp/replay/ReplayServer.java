package rotp.replay;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Embedded headless LAN viewer. Each browser keeps its own playback cursor. */
public final class ReplayServer implements Closeable {
    private final HttpServer http;
    private final ReplayLibrary library;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private static final Gson JSON = new Gson();

    public static ReplayServer start(Path directory, String bind, int port) throws IOException {
        var server = new ReplayServer(directory, bind, port);
        server.http.start();
        return server;
    }

    private ReplayServer(Path directory, String bind, int port) throws IOException {
        library = new ReplayLibrary(directory);
        http = HttpServer.create(new InetSocketAddress(bind, port), 32);
        http.setExecutor(workers);
        http.createContext("/", this::handle);
    }

    public int port() { return http.getAddress().getPort(); }

    @Override public void close() throws IOException {
        http.stop(0);
        workers.shutdownNow();
        library.close();
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            try {
                var headers = exchange.getResponseHeaders();
                headers.add("X-Content-Type-Options","nosniff");
                headers.add("Cache-Control","no-store");
                headers.add("Content-Security-Policy","default-src 'self'; img-src 'self' blob:; style-src 'self' 'unsafe-inline'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'");
                if (!exchange.getRequestMethod().equals("GET")) {
                    headers.set("Allow","GET");
                    error(exchange,405,"Use GET for replay requests");
                } else route(exchange);
            } catch (NoSuchFileException failure) {
                error(exchange,404,"Replay or resource unavailable");
            } catch (IllegalArgumentException failure) {
                error(exchange,400,"Invalid replay request");
            } catch (IOException failure) {
                if (exchange.getResponseCode() == -1) error(exchange,422,ReplayLibrary.archiveError(failure));
            } catch (RuntimeException failure) {
                if (exchange.getResponseCode() == -1) error(exchange,500,"Unable to read replay");
            }
        } catch (IOException disconnected) { /* Observer left while reading. */ }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        switch (path) {
            case "/", "/index.html" -> asset(exchange, "index.html","text/html; charset=utf-8");
            case "/app.js" -> asset(exchange, "app.js","text/javascript; charset=utf-8");
            case "/style.css" -> asset(exchange, "style.css","text/css; charset=utf-8");
            case "/api/replays" -> json(exchange, 200, library.list());
            default -> replay(exchange, path);
        }
    }

    private void replay(HttpExchange exchange, String path) throws IOException {
        String[] parts = path.split("/", -1);
        if (parts.length < 4 || !parts[1].equals("api") || !parts[2].equals("replays")
                || !parts[3].matches("[0-9a-f-]{36}")) throw new NoSuchFileException("Unknown resource");
        String id = parts[3];
        if (parts.length == 4) {
            json(exchange,200,library.metadata(id));
        } else if (parts.length == 5 && parts[4].equals("outcome")) {
            json(exchange,200,library.outcome(id));
        } else if (parts.length == 5 && parts[4].equals("events")) {
            var query = query(exchange.getRequestURI());
            int frame = unsigned(query.getOrDefault("maxFrame","0"));
            String category = query.getOrDefault("category","all");
            Integer empire = query.containsKey("empire") ? unsigned(query.get("empire")) : null;
            Integer offset = query.containsKey("offset") ? unsigned(query.get("offset")) : null;
            int limit = unsigned(query.getOrDefault("limit","50"));
            if (limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid page size");
            json(exchange,200,library.events(id,frame,category,empire,offset,limit));
        } else if (parts.length == 6 && parts[4].equals("map")) {
            bytes(exchange,200,"image/png",library.map(id,unsigned(parts[5])));
        } else if (parts.length == 6 && parts[4].equals("files")) {
            var file = library.download(id,parts[5]);
            try (var channel = Files.newByteChannel(file.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                exchange.getResponseHeaders().set("Content-Type",file.contentType());
                String name = URLEncoder.encode(file.path().getFileName().toString(),StandardCharsets.UTF_8).replace("+","%20");
                exchange.getResponseHeaders().set("Content-Disposition","attachment; filename*=UTF-8''" + name);
                exchange.sendResponseHeaders(200,channel.size());
                Channels.newInputStream(channel).transferTo(exchange.getResponseBody());
            }
        } else throw new NoSuchFileException("Unknown resource");
    }

    private static Map<String,String> query(URI uri) {
        var result = new HashMap<String,String>();
        if (uri.getRawQuery() == null) return result;
        for (String field : uri.getRawQuery().split("&")) {
            String[] pair = field.split("=",2);
            if (pair.length != 2) throw new IllegalArgumentException("Expected query parameter");
            result.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8));
        }
        return result;
    }

    private static int unsigned(String input) {
        int number = Integer.parseInt(input);
        if (number < 0) throw new IllegalArgumentException("Expected nonnegative integer");
        return number;
    }

    private static void asset(HttpExchange exchange, String name, String type) throws IOException {
        try (var input = ReplayServer.class.getResourceAsStream("web/" + name)) {
            if (input == null) throw new NoSuchFileException(name);
            bytes(exchange,200,type,input.readAllBytes());
        }
    }

    private static void bytes(HttpExchange exchange, int status, String type, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type",type);
        exchange.sendResponseHeaders(status,data.length);
        exchange.getResponseBody().write(data);
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        bytes(exchange,status,"application/json; charset=utf-8",JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange,status,Map.of("error",message));
    }

    public static int run(String[] args) {
        String bind = "0.0.0.0";
        Path directory = Path.of("replays");
        int port = 8080;
        String usage = "--serve-replays [--dir replays] [--bind 0.0.0.0] [--port 8080]";
        try {
            for (int i = 1; i < args.length; i++) {
                String key = args[i];
                if (key.equals("--help")) { System.out.println(usage); return 0; }
                if (++i == args.length) throw new IllegalArgumentException(usage);
                switch (key) {
                    case "--dir" -> directory = Path.of(args[i]);
                    case "--bind" -> bind = args[i];
                    case "--port" -> port = unsigned(args[i]);
                    default -> throw new IllegalArgumentException(usage);
                }
            }
            Files.createDirectories(directory);
            try (var server = start(directory,bind,port)) {
                System.out.println("Replay library: " + directory.toRealPath());
                var address = server.http.getAddress().getAddress();
                var ips = new LinkedHashSet<InetAddress>();
                if (address.isAnyLocalAddress()) {
                    ips.add(InetAddress.getLoopbackAddress());
                    for (var network : Collections.list(NetworkInterface.getNetworkInterfaces()))
                        if (network.isUp()) for (var ip : Collections.list(network.getInetAddresses()))
                            if (ip instanceof Inet4Address) ips.add(ip);
                } else ips.add(address);
                for (var ip : ips) {
                    String host = ip.getHostAddress();
                    if (ip instanceof Inet6Address) host = "[" + host + "]";
                    System.out.println("Replay viewer: http://" + host + ":" + server.port() + "/");
                }
                new CountDownLatch(1).await();
            }
            return 0;
        } catch (IllegalArgumentException failure) {
            System.err.println(usage);
            return 2;
        } catch (IOException failure) {
            System.err.println("Cannot serve replays: " + failure.getMessage());
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
}
