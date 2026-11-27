package music;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class RecommendationServerTest {
    public static void main(String[] args) throws Exception {
        var model = new Recommender(MusicData.load(Path.of("examples/tracks.tsv"), Path.of("examples/listens.tsv")));
        try (var server = RecommendationServer.start(model, 0, 5)) {
            var client = HttpClient.newHttpClient();
            assertReadiness(client, server.port());
            var maya = request(client, server.port(), "?user=maya&limit=3");
            var theo = request(client, server.port(), "?user=theo&limit=1");
            assertRanking(maya.get(10, TimeUnit.SECONDS), model.recommend("maya", 3));
            assertRanking(theo.get(10, TimeUnit.SECONDS), model.recommend("theo", 1));

            assertBadRequest(client, server.port(), "?user=maya&limti=1", "Unknown query parameter 'limti'");
            assertBadRequest(client, server.port(), "?user=maya&bad%22name=x", "Unknown query parameter 'bad\\\"name'");
            for (String repeated : List.of("limit=1&limit=3", "limit=3&limit=1", "limit=1&%6cimit=1")) {
                assertBadRequest(client, server.port(), "?user=maya&" + repeated, "Duplicate query parameter 'limit'");
            }
            assertBadRequest(client, server.port(), "?user=maya&user=theo", "Duplicate query parameter 'user'");
            assertBadRequest(client, server.port(), "?user=maya&max-per-artist=1&max-per-artist=2",
                    "Duplicate query parameter 'max-per-artist'");
            var encoded = request(client, server.port(), "?user=maya&%6cimit=1");
            assertRanking(encoded.get(10, TimeUnit.SECONDS), model.recommend("maya", 1));

            var favorites = List.of("t01", "t04");
            var seeded = request(client, server.port(), "?user=new&seed-track=t01&seed-track=t04");
            assertRanking(seeded.get(10, TimeUnit.SECONDS),
                    model.recommend("new", 5, Integer.MAX_VALUE, Set.of(), favorites));
            var capped = request(client, server.port(), "?user=new&limit=14&max-per-artist=1");
            assertRanking(capped.get(10, TimeUnit.SECONDS), model.recommend("new", 14, 1));
            var combined = request(client, server.port(), "?user=new&limit=14&max-per-artist=1&seed-track=t01&seed-track=t04");
            assertRanking(combined.get(10, TimeUnit.SECONDS), model.recommend("new", 14, 1, Set.of(), favorites));
            for (String cap : List.of("", "three", "2147483648")) {
                assertBadRequest(client, server.port(), "?user=new&max-per-artist=" + cap, "max-per-artist requires a positive integer");
            }
            for (String cap : List.of("0", "-1")) {
                assertBadRequest(client, server.port(), "?user=new&max-per-artist=" + cap, "Maximum tracks per artist must be at least 1");
            }
            assertBadRequest(client, server.port(), "?user=new&seed-track=absent", "Unknown seed track ID 'absent'");
            assertBadRequest(client, server.port(), "?user=new&seed-track=", "Unknown seed track ID ''");
            assertRanking(request(client, server.port(), "?user=new").get(10, TimeUnit.SECONDS), model.recommend("new", 5));

            for (String query : new String[]{"", "?limit=1", "?user=", "?user=%20"}) {
                assertBadRequest(client, server.port(), query, "User ID must not be blank");
            }
            for (String limit : new String[]{"0", "-1"}) {
                assertBadRequest(client, server.port(), "?user=maya&limit=" + limit, "Limit must be at least 1");
            }
            for (String limit : new String[]{"", "three", "2147483648"}) {
                assertBadRequest(client, server.port(), "?user=maya&limit=" + limit, "limit requires a positive integer");
            }
        }
        assertEscapedTitle();
        assertListenerHistoryExtremes();
    }

    private static void assertReadiness(HttpClient client, int port) throws Exception {
        var uri = URI.create("http://127.0.0.1:" + port + "/ready");
        var body = HttpResponse.BodyHandlers.ofString();
        var ready = client.send(HttpRequest.newBuilder(uri).build(), body);
        if (ready.statusCode() != 200 || !ready.body().equals("{\"status\":\"ready\"}")
                || !ready.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) {
            throw new AssertionError("Unexpected readiness response: " + ready + " " + ready.body());
        }
        var post = client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(), body);
        if (post.statusCode() != 405 || !post.headers().firstValue("Allow").orElse("").equals("GET")) {
            throw new AssertionError("Unexpected readiness method response: " + post);
        }
        for (String path : List.of("/", "/missing", "/ready/extra", "/recommendations/extra")) {
            var response = client.send(HttpRequest.newBuilder(uri.resolve(path)).build(), body);
            if (response.statusCode() != 404 || !response.body().equals("{\"error\":\"Unknown path\"}")
                    || !response.headers().firstValue("Content-Type").orElse("").equals("application/json; charset=utf-8")) {
                throw new AssertionError("Unexpected response for " + path + ": " + response + " " + response.body());
            }
        }
    }

    private static void assertListenerHistoryExtremes() throws Exception {
        var first = new Track("a", "First", "Artist", "Jazz");
        var favorite = new Track("z", "Favorite", "Artist", "Jazz");
        var history = List.of(new Listening("regular", "a", 1), new Listening("regular", "z", 9));
        var model = new Recommender(new MusicData(List.of(first, favorite), history));
        try (var server = RecommendationServer.start(model, 0, 1, Map.of("hidden", Set.of("a", "z")))) {
            var client = HttpClient.newHttpClient();
            var newcomer = request(client, server.port(), "?user=new").get(10, TimeUnit.SECONDS);
            assertRanking(newcomer, List.of(new Recommender.Recommendation(
                    favorite, 1.0, "Popular across listening history")));

            var hidden = request(client, server.port(), "?user=hidden").get(10, TimeUnit.SECONDS);
            assertRanking(hidden, List.of());
            var regular = request(client, server.port(), "?user=regular").get(10, TimeUnit.SECONDS);
            assertRanking(regular, List.of());
        }
    }

    private static void assertEscapedTitle() throws Exception {
        var track = new Track("t1", "Café \"Live\" \\ Encore", "Artist", "Jazz");
        var model = new Recommender(new MusicData(List.of(track), List.of()));
        try (var server = RecommendationServer.start(model, 0, 1)) {
            var response = request(HttpClient.newHttpClient(), server.port(), "?user=new")
                    .get(10, TimeUnit.SECONDS);
            String expectedTitle = "\"title\":\"Café \\\"Live\\\" \\\\ Encore\"";
            if (response.statusCode() != 200 || !response.body().contains(expectedTitle)) {
                throw new AssertionError("Unexpected JSON title in " + response.body());
            }
        }
    }

    private static CompletableFuture<HttpResponse<String>> request(HttpClient client, int port, String query) {
        var uri = URI.create("http://127.0.0.1:" + port + "/recommendations" + query);
        return client.sendAsync(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertBadRequest(HttpClient client, int port, String query, String message) throws Exception {
        var response = request(client, port, query).get(10, TimeUnit.SECONDS);
        String mediaType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0];
        String expected = "{\"error\":\"" + message + "\"}";
        if (response.statusCode() != 400 || !mediaType.equalsIgnoreCase("application/json")
                || !expected.equals(response.body())) {
            throw new AssertionError("Unexpected error response for " + query + ": " + response + " " + response.body());
        }
    }

    private static void assertRanking(HttpResponse<String> response, List<Recommender.Recommendation> picks) {
        var expected = new StringJoiner(",", "{\"recommendations\":[", "]}");
        for (var pick : picks) {
            var track = pick.track();
            expected.add("""
                    {"id":"%s","title":"%s","artist":"%s","genre":"%s","score":%s,"explanation":"%s"}"""
                    .formatted(track.id(), track.title(), track.artist(), track.genre(), pick.score(), pick.reason()));
        }
        if (response.statusCode() != 200 || !expected.toString().equals(response.body())) {
            throw new AssertionError("Expected " + expected + " but got " + response + " " + response.body());
        }
    }
}
