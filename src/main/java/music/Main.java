package music;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

public final class Main {
    private static final String USAGE = """
            Usage: java -jar music-recommender.jar <command> [options]

              recommend USER    Rank unheard tracks for a listener
              hide USER TRACK   Hide a track for one listener (requires --feedback)
              unhide USER TRACK   Restore a track for one listener (requires --feedback)
              serve             Serve JSON recommendations on loopback
              evaluate          Compare personalized and popularity rankings

            Options:
              --limit N         Maximum recommendations (default 5)
              --max-per-artist N  Maximum tracks per artist for recommend (default unlimited)
              --port N          HTTP port for serve (default 8080, 0 for ephemeral)
              --tracks FILE     Track catalog (default examples/tracks.tsv)
              --listens FILE    Listening history (default examples/listens.tsv)
              --feedback FILE   Listener feedback for recommend, hide, and unhide
              --events FILE     Timestamped plays for chronological evaluation
              --cutoff TIME     Evaluation boundary with UTC offset (requires --events)
              --help            Show usage
            """;

    private Main() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            out.print(USAGE);
            return 0;
        }

        try {
            String user = null;
            String feedbackTrack = null;
            int next = 1;
            switch (args[0]) {
                case "recommend", "hide", "unhide" -> {
                    if (args.length < 2 || args[1].isBlank() || args[1].startsWith("--")) {
                        throw new IllegalArgumentException(args[0] + " requires a listener ID");
                    }
                    user = args[next++];
                    if (!args[0].equals("recommend")) {
                        if (next == args.length || args[next].startsWith("--")) {
                            throw new IllegalArgumentException(args[0] + " requires a track ID");
                        }
                        feedbackTrack = args[next++];
                    }
                }
                case "serve", "evaluate" -> { }
                default -> throw new IllegalArgumentException("unknown command: " + args[0]);
            }

            Path tracks = Path.of("examples", "tracks.tsv");
            Path listens = null;
            Path events = null;
            Path feedback = null;
            Instant cutoff = null;
            int limit = 5;
            int maxPerArtist = Integer.MAX_VALUE;
            int port = 8080;
            while (next < args.length) {
                String option = args[next++];
                if (next == args.length || args[next].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = args[next++];
                if (feedbackTrack != null && !option.equals("--tracks") && !option.equals("--feedback")) {
                    throw new IllegalArgumentException("Unsupported " + args[0] + " option: " + option);
                }
                switch (option) {
                    case "--tracks" -> tracks = Path.of(value);
                    case "--listens" -> listens = Path.of(value);
                    case "--feedback" -> {
                        if (user == null) throw new IllegalArgumentException("--feedback requires recommend, hide, or unhide");
                        feedback = Path.of(value);
                    }
                    case "--events" -> events = Path.of(value);
                    case "--cutoff" -> {
                        try {
                            cutoff = OffsetDateTime.parse(value).toInstant();
                        } catch (DateTimeParseException e) {
                            throw new IllegalArgumentException("--cutoff requires an ISO-8601 timestamp with UTC offset");
                        }
                    }
                    case "--port" -> {
                        if (!args[0].equals("serve")) throw new IllegalArgumentException("--port requires serve");
                        try {
                            port = Integer.parseInt(value);
                            if (port < 0 || port > 65535) throw new NumberFormatException();
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("--port requires an integer from 0 to 65535");
                        }
                    }
                    case "--max-per-artist" -> {
                        if (user == null) throw new IllegalArgumentException("--max-per-artist requires recommend");
                        maxPerArtist = parsePositive(option, value);
                    }
                    case "--limit" -> limit = parsePositive(option, value);
                    default -> throw new IllegalArgumentException("unknown option: " + option);
                }
            }

            if (feedbackTrack != null) {
                if (feedback == null) throw new IllegalArgumentException(args[0] + " requires --feedback FILE");
                MusicData.setTrackHidden(feedback, user, feedbackTrack, MusicData.loadCatalog(tracks), args[0].equals("hide"));
                return 0;
            }
            if (events != null || cutoff != null) {
                if (!args[0].equals("evaluate") || events == null || cutoff == null || listens != null) {
                    throw new IllegalArgumentException("Use --events and --cutoff together with evaluate, without --listens");
                }
                var catalog = MusicData.loadCatalog(tracks);
                var report = Evaluator.evaluateDiscovery(catalog, MusicData.loadEvents(events, catalog), cutoff, limit);
                out.println("Eligible listeners: " + report.rankings().listeners());
                out.println("Skipped without training history: " + report.missingTraining());
                out.println("Skipped without future discovery: " + report.missingDiscovery());
                if (report.rankings().listeners() == 0) {
                    out.println("No eligible listeners at this cutoff.");
                } else {
                    printMetrics(report.rankings(), limit, out);
                }
                return 0;
            }

            MusicData data = MusicData.load(tracks, listens == null ? Path.of("examples", "listens.tsv") : listens);
            if (args[0].equals("serve")) {
                try {
                    var server = RecommendationServer.start(new Recommender(data), port, limit);
                    out.println("Listening on http://127.0.0.1:" + server.port());
                } catch (IOException e) {
                    err.println("Could not start HTTP server: " + e.getMessage());
                    return 1;
                }
            } else if (user == null) {
                printEvaluation(Evaluator.evaluate(data, limit), limit, out);
            } else {
                Set<String> hidden = feedback == null ? Set.of() : MusicData.loadHiddenTracks(feedback, user, data.tracks());
                var picks = new Recommender(data).recommend(user, limit, maxPerArtist, hidden);
                if (picks.isEmpty()) {
                    out.println("No unheard tracks available.");
                } else {
                    out.println("rank\tid\ttitle\tartist\tgenre\tscore\treason");
                    int rank = 0;
                    for (var pick : picks) {
                        Track track = pick.track();
                        out.printf(Locale.ROOT, "%d\t%s\t%s\t%s\t%s\t%.3f\t%s%n",
                                ++rank, track.id(), track.title(), track.artist(), track.genre(),
                                pick.score(), pick.reason());
                    }
                }
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("Could not read music data: " + e.getMessage());
            return 1;
        }
    }

    private static int parsePositive(String option, String value) {
        try {
            int limit = Integer.parseInt(value);
            if (limit > 0) {
                return limit;
            }
        } catch (NumberFormatException ignored) {
            // Non-numeric and out-of-range values get the same usage hint.
        }
        throw new IllegalArgumentException(option + " requires a positive integer");
    }

    private static void printEvaluation(Evaluator.Report report, int limit, PrintStream out) {
        if (report.listeners() == 0) {
            out.println("Evaluation requires a listener with at least two distinct tracks.");
            return;
        }
        out.println("Evaluated listeners: " + report.listeners());
        printMetrics(report, limit, out);
    }

    private static void printMetrics(Evaluator.Report report, int limit, PrintStream out) {
        out.printf("model\thit_rate@%d\tmrr@%d%n", limit, limit);
        out.printf(Locale.ROOT, "personalized\t%.3f\t%.3f%n",
                report.hitRate(), report.meanReciprocalRank());
        out.printf(Locale.ROOT, "popularity\t%.3f\t%.3f%n",
                report.popularityHitRate(), report.popularityMeanReciprocalRank());
    }
}
