package music;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MainTest {
    public static void main(String[] args) throws Exception {
        check(run("recommend", "--help").out().contains("Usage:"), "Help for recommend");
        Result badLimit = run("recommend", "maya", "--limit", "0");
        check(badLimit.status() != 0 && badLimit.err().contains("positive integer"), "Invalid limit");
        check(run("recommend").status() != 0, "Missing listener");
        check(run("evaluate", "--tracks").status() != 0, "Missing option value");

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

            Files.writeString(listens, "user_id\ttrack_id\tplays\nalex\ts1\t8\nalex\ts2\t2\n");
            check(run(recommend).out().contains("No unheard tracks"), "Entire catalog heard");
            Result eval = run("evaluate", "--tracks", tracks.toString(), "--listens", listens.toString(),
                    "--limit", "1");
            check(eval.status() == 0 && eval.out().contains("personalized\t1.000\t1.000")
                    && eval.out().contains("popularity\t1.000\t1.000"), "Metrics for both models");
        } finally {
            Files.deleteIfExists(listens);
            Files.deleteIfExists(tracks);
            Files.delete(dir);
        }
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
