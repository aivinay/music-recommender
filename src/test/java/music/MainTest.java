package music;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class MainTest {
    public static void main(String[] args) throws Exception {
        check(run("recommend", "--help").out().contains("Usage:"), "Help for recommend");
        Result badLimit = run("recommend", "maya", "--limit", "0");
        check(badLimit.status() != 0 && badLimit.err().contains("positive integer"), "Invalid limit");
        check(run("recommend").status() != 0, "Missing listener");
        check(run("evaluate", "--tracks").status() != 0, "Missing option value");

        testOccupiedPort();
        testFavoriteTracks();
        String events = "examples/chronological-plays.tsv";
        Result discovery = run("evaluate", "--events", events, "--cutoff", "2025-12-31T19:00:00-05:00", "--limit", "3");
        check(discovery.status() == 0 && discovery.out().contains("Eligible listeners: 1")
                && discovery.out().contains("Skipped without training history: 1")
                && discovery.out().contains("Skipped without future discovery: 1")
                && discovery.out().contains("personalized\t1.000\t1.000")
                && discovery.out().contains("popularity\t1.000\t0.333"), "Chronological fixture metrics");
        Result empty = run("evaluate", "--events", events, "--cutoff", "2000-01-01T00:00:00Z");
        check(empty.status() == 0 && empty.out().contains("No eligible listeners"), "Empty split");
        check(run("evaluate", "--events", events).status() == 2, "Missing cutoff");
        check(run("evaluate", "--events", events, "--cutoff", "2026-01-01T00:00:00").status() == 2,
                "Cutoff without offset");

        Path dir = Files.createTempDirectory("music-cli");
        Path tracks = dir.resolve("tracks.tsv");
        Path listens = dir.resolve("listens.tsv");
        Path feedback = dir.resolve("feedback.tsv");
        try {
            Files.writeString(tracks, "id\ttitle\tartist\tgenre\ns1\tOne\tDuo\tJazz\ns2\tTwo\tDuo\tJazz\n");
            Files.writeString(listens, "user_id\ttrack_id\tplays\nalex\ts1\t8\n");
            Result capped = run("recommend", "new", "--tracks", tracks.toString(), "--listens", listens.toString(),
                    "--max-per-artist", "1");
            check(capped.status() == 0 && capped.out().lines().count() == 2 && capped.out().contains("1\ts1\t"),
                    "Artist cap keeps the more popular track");
            for (String invalid : java.util.List.of("0", "-1", "NaN")) {
                Result error = run("recommend", "maya", "--max-per-artist", invalid);
                check(error.status() == 2 && error.err().contains("--max-per-artist"), "Invalid artist cap");
            }
            check(run("evaluate", "--max-per-artist", "1").status() == 2, "Artist cap requires recommend");
            String[] recommend = {"recommend", "alex", "--tracks", tracks.toString(),
                    "--listens", listens.toString(), "--limit", "1"};
            Result result = run(recommend);
            check(result.status() == 0 && result.err().isEmpty(), "Successful recommendation");
            check(result.out().lines().count() == 2 && result.out().contains("1\ts2\tTwo\tDuo\tJazz\t"),
                    "Ranked TSV output includes the unheard song");

            String[] withFeedback = {"recommend", "new", "--tracks", tracks.toString(), "--listens",
                    listens.toString(), "--feedback", feedback.toString(), "--limit", "1"};
            String[] hide = {"hide", "new", "s1", "--tracks", tracks.toString(), "--feedback", feedback.toString()};
            check(run(hide).status() == 0 && run(hide).status() == 0, "Repeat hide succeeds");
            String saved = Files.readString(feedback);
            check(saved.equals("user_id\ttrack_id\nnew\ts1\n") && run(withFeedback).out().contains("1\ts2\t"),
                    "One persisted hide filters the next invocation");
            check(run("hide", "new", "unknown", "--tracks", tracks.toString(), "--feedback", feedback.toString())
                    .status() != 0 && saved.equals(Files.readString(feedback)), "Unknown track preserves feedback");
            String header = "user_id\ttrack_id\n";
            Files.writeString(feedback, header + "new\ts1\nnew\ts1\nother\ts2\n");
            Result filtered = run(withFeedback);
            check(filtered.status() == 0 && filtered.out().contains("1\ts2\t"),
                    "Duplicate hides and another listener's feedback leave the next track available");
            String[] restore = {"unhide", "new", "s1", "--tracks", tracks.toString(), "--feedback", feedback.toString()};
            check(run(restore).status() == 0 && run(restore).status() == 0, "Repeat restore succeeds");
            check(Files.readString(feedback).equals(header + "other\ts2\n"),
                    "Restore removes all matching rows and preserves the other listener");
            check(run(withFeedback).out().contains("1\ts1\t"), "Restored track regains its original rank");
            Files.writeString(feedback, header + "new\ts1\nnew\ts2\n");
            check(run(withFeedback).out().contains("No unheard tracks"), "Entire catalog hidden");
            Files.writeString(feedback, header + "new\ts1\nother\tmissing\n");
            Result invalid = run(withFeedback);
            check(invalid.status() == 1 && invalid.err().contains(feedback + ":3:")
                    && invalid.err().contains("missing"), "Unknown feedback track includes file and line");

            Files.writeString(listens, "user_id\ttrack_id\tplays\nalex\ts1\t8\nalex\ts2\t2\n");
            check(run(recommend).out().contains("No unheard tracks"), "Entire catalog heard");
            Result eval = run("evaluate", "--tracks", tracks.toString(), "--listens", listens.toString(),
                    "--limit", "1");
            check(eval.status() == 0 && eval.out().contains("personalized\t1.000\t1.000")
                    && eval.out().contains("popularity\t1.000\t1.000"), "Metrics for both models");
        } finally {
            Files.deleteIfExists(feedback.resolveSibling("feedback.tsv.lock"));
            Files.deleteIfExists(feedback);
            Files.deleteIfExists(listens);
            Files.deleteIfExists(tracks);
            Files.delete(dir);
        }
    }

    private static void testOccupiedPort() throws Exception {
        var model = new Recommender(MusicData.load(Path.of("examples/tracks.tsv"), Path.of("examples/listens.tsv")));
        try (var existing = RecommendationServer.start(model, 0, 5)) {
            String endpoint = "127.0.0.1:" + existing.port();
            Result conflict = run("serve", "--port", Integer.toString(existing.port()));
            check(conflict.status() == 1 && conflict.out().isEmpty() && conflict.err().contains(endpoint),
                    "A port collision must identify the requested endpoint without announcing startup");
            var request = HttpRequest.newBuilder(URI.create("http://" + endpoint + "/ready"))
                    .timeout(Duration.ofSeconds(5)).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            check(response.statusCode() == 200 && response.body().equals("{\"status\":\"ready\"}"),
                    "The original service must still answer after a port collision");
        }
    }

    private static void testFavoriteTracks() {
        Result popular = run("recommend", "newcomer", "--limit", "1");
        check(popular.status() == 0 && popular.out().contains("1\tt09\t"), "Demo popularity leader");
        Result seeded = run("recommend", "newcomer", "--seed-track", "t01", "--limit", "14");
        int related = seeded.out().indexOf("\tt03\t");
        int popularityLeader = seeded.out().indexOf("\tt09\t");
        check(seeded.status() == 0 && related >= 0 && related < popularityLeader,
                "Favorite promotes a related demo track above the popularity leader");
        Result repeated = run("recommend", "newcomer", "--seed-track", "t01", "--seed-track", "t01", "--limit", "14");
        check(seeded.equals(repeated), "Repeating a seed preserves the ranking and score");
        Result twoSeeds = run("recommend", "newcomer", "--seed-track", "t01", "--seed-track", "t03", "--limit", "20");
        check(twoSeeds.status() == 0 && !twoSeeds.out().contains("\tt01\t") && !twoSeeds.out().contains("\tt03\t"),
                "Both favorites are excluded");
        Result unknown = run("recommend", "newcomer", "--seed-track", "missing");
        check(unknown.status() == 2 && unknown.err().contains("missing"), "Unknown seed identifies the track");
        check(run("evaluate", "--seed-track", "t01").status() == 2, "Seeds require recommend");
    }

    private record Result(int status, String out, String err) {}

    private static Result run(String... args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        try (var out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             var err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            int status = Main.run(args, out, err);
            return new Result(status, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private static void check(boolean success, String message) {
        if (!success) {
            throw new AssertionError(message);
        }
    }
}
