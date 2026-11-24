package music;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class RecommendationServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor workers;

    private RecommendationServer(HttpServer server) {
        this.server = server;
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(workers);
    }

    int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }

    static RecommendationServer start(Recommender model, int port, int defaultLimit) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/recommendations", exchange -> {
            try {
                if (!exchange.getRequestURI().getPath().equals("/recommendations")) {
                    reply(exchange, 404, "{\"error\":\"Unknown path\"}");
                } else if (!exchange.getRequestMethod().equals("GET")) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    reply(exchange, 405, "{\"error\":\"GET required\"}");
                } else {
                    handle(exchange, model, defaultLimit);
                }
            } finally {
                exchange.close();
            }
        });
        var service = new RecommendationServer(server);
        server.start();
        return service;
    }

    private static void handle(HttpExchange exchange, Recommender model, int limit) throws IOException {
        String user = null;
        int maxPerArtist = Integer.MAX_VALUE;
        List<String> seeds = new ArrayList<>();
        String query = exchange.getRequestURI().getRawQuery();
        try {
            if (query != null) {
                for (String field : query.split("&")) {
                    String[] parts = field.split("=", 2);
                    String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                    switch (URLDecoder.decode(parts[0], StandardCharsets.UTF_8)) {
                        case "user" -> user = value;
                        case "seed-track" -> seeds.add(value);
                        case "limit" -> limit = parseInteger("limit", value);
                        case "max-per-artist" -> maxPerArtist = parseInteger("max-per-artist", value);
                    }
                }
            }
            reply(exchange, 200, json(model.recommend(user, limit, maxPerArtist, Set.of(), seeds)));

        } catch (IllegalArgumentException e) {
            reply(exchange, 400, "{\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private static int parseInteger(String parameter, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(parameter + " requires a positive integer");
        }
    }

    private static String json(List<Recommender.Recommendation> picks) {
        var result = new StringJoiner(",", "{\"recommendations\":[", "]}");
        for (var pick : picks) {
            Track track = pick.track();
            result.add("{\"id\":" + quote(track.id()) + ",\"title\":" + quote(track.title())
                    + ",\"artist\":" + quote(track.artist()) + ",\"genre\":" + quote(track.genre())
                    + ",\"score\":" + pick.score() + ",\"explanation\":" + quote(pick.reason()) + "}");
        }
        return result.toString();
    }

    private static String quote(String value) {
        var result = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            if (ch == '"' || ch == '\\') result.append('\\');
            if (ch < 0x20) result.append(String.format("\\u%04x", (int) ch));
            else result.append(ch);
        }
        return result.append('"').toString();
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
