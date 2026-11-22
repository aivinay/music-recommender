package music;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class MusicDataTest {
    private static final String CATALOG_HEADER = "id\ttitle\tartist\tgenre\n";
    private static final String HISTORY_HEADER = "user_id\ttrack_id\tplays\n";
    private static final String TRACK = "t1\tAmanhã\tÁgua\tSamba\n";
    private static final String PLAYS = "u1\tt1\t3\n";

    public static void main(String[] args) throws IOException {
        testDomainValidation();
        testEventTable();
        Path dir = Files.createTempDirectory("music-data-test");
        Path catalog = dir.resolve("catalog.tsv");
        Path history = dir.resolve("history.tsv");
        try {
            Files.writeString(catalog, CATALOG_HEADER + TRACK);
            Files.writeString(history, HISTORY_HEADER + PLAYS + "u2\tt1\t1\n");
            MusicData data = MusicData.load(catalog, history);
            check(data.tracks().equals(List.of(new Track("t1", "Amanhã", "Água", "Samba"))),
                    "Expected UTF-8 track metadata to round-trip");
            check(data.history().equals(List.of(new Listening("u1", "t1", 3),
                    new Listening("u2", "t1", 1))), "Expected separate play counts per listener");

            Files.writeString(catalog, CATALOG_HEADER);
            Files.writeString(history, HISTORY_HEADER);
            MusicData empty = MusicData.load(catalog, history);
            check(empty.tracks().isEmpty() && empty.history().isEmpty(),
                    "Header-only files should yield empty data");

            expectInvalid(catalog, history, catalog, "", 1);
            expectInvalid(catalog, history, catalog, "track\ttitle\tartist\tgenre\n", 1);
            expectInvalid(catalog, history, catalog, CATALOG_HEADER + "t1\tTitle\tArtist\n", 2);
            expectInvalid(catalog, history, catalog, CATALOG_HEADER + "t1\tTitle\tArtist\t\n", 2);
            expectInvalid(catalog, history, catalog, CATALOG_HEADER + TRACK + TRACK, 3);

            Files.writeString(catalog, CATALOG_HEADER + TRACK);
            expectInvalid(catalog, history, history, "track_id\tuser_id\tplays\n", 1);
            expectInvalid(catalog, history, history, HISTORY_HEADER + PLAYS + PLAYS, 3);
            expectInvalid(catalog, history, history, HISTORY_HEADER + "u1\tmissing\t1\n", 2);
            expectInvalid(catalog, history, history, HISTORY_HEADER + "\tt1\t1\n", 2);
            for (String plays : List.of("0", "-1", "1.5", "many", "2147483648")) {
                expectInvalid(catalog, history, history, HISTORY_HEADER + "u1\tt1\t" + plays + "\n", 2);
            }
        } finally {
            Files.deleteIfExists(history);
            Files.deleteIfExists(catalog);
            Files.deleteIfExists(dir);
        }
    }

    private static void testEventTable() throws IOException {
        Path file = Files.createTempFile("plays", ".tsv");
        List<Track> catalog = List.of(new Track("t1", "Title", "Artist", "Genre"));
        String header = "user_id\ttrack_id\tplayed_at\n";
        String row = "u1\tt1\t2026-01-01T00:00:00Z\n";
        try {
            Files.writeString(file, header + row + "u1\tt1\t2025-12-31T19:00:00-05:00\n");
            var plays = MusicData.loadEvents(file, catalog);
            var expected = new MusicData.PlayEvent("u1", "t1", java.time.Instant.parse("2026-01-01T00:00:00Z"));
            check(plays.equals(List.of(expected, expected)), "Preserve repeated plays and normalize offsets");
            for (String invalid : List.of("t2\t2026-01-01T00:00:00Z", "t1\t2026-01-01T00:00:00",
                    "t1\t2026-02-30T00:00:00Z")) {
                Files.writeString(file, header + row + "u1\t" + invalid + "\n");
                try {
                    MusicData.loadEvents(file, catalog);
                    throw new AssertionError("Accepted invalid event " + invalid);
                } catch (IOException error) {
                    check(error.getMessage().startsWith(file + ":3:"), "Missing event location: " + error);
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void testDomainValidation() {
        for (String invalid : new String[] {null, "  "}) {
            rejects("track ID", () -> new Track(invalid, "Title", "Artist", "Genre"));
            rejects("title", () -> new Track("t1", invalid, "Artist", "Genre"));
            rejects("artist", () -> new Track("t1", "Title", invalid, "Genre"));
            rejects("genre", () -> new Track("t1", "Title", "Artist", invalid));
            rejects("listener ID", () -> new Listening(invalid, "t1", 1));
            rejects("history track ID", () -> new Listening("u1", invalid, 1));
        }
        rejects("zero plays", () -> new Listening("u1", "t1", 0));
        rejects("negative plays", () -> new Listening("u1", "t1", -1));

        Track track = new Track("t1", "Title", "Artist", "Genre");
        Track sameId = new Track("t1", "Other title", "Other artist", "Other genre");
        Listening heard = new Listening("u1", "t1", 3);
        Listening samePair = new Listening("u1", "t1", 1);
        Listening unknown = new Listening("u1", "missing", 1);
        rejects("duplicate track IDs", () -> new MusicData(List.of(track, sameId), List.of()));
        rejects("unknown track reference", () -> new MusicData(List.of(track), List.of(unknown)));
        rejects("duplicate listener-track pair", () -> new MusicData(List.of(track), List.of(heard, samePair)));
    }

    private static void rejects(String description, Runnable construct) {
        try {
            construct.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected constructor to reject " + description);
    }

    private static void expectInvalid(Path catalog, Path history, Path file, String content, int line)
            throws IOException {
        Files.writeString(file, content);
        String location = file + ":" + line + ":";
        try {
            MusicData.load(catalog, history);
        } catch (IOException e) {
            check(e.getMessage().contains(location), "Expected " + location + " in " + e.getMessage());
            return;
        }
        throw new AssertionError("Expected loader to reject " + location);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
