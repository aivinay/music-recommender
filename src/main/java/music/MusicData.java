package music;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** A snapshot of the catalog and its aggregated listening history. */
public record MusicData(List<Track> tracks, List<Listening> history) {
    public MusicData {
        tracks = List.copyOf(tracks);
        history = List.copyOf(history);
        Set<String> trackIds = new HashSet<>();
        for (int i = 0; i < tracks.size(); i++) {
            String id = tracks.get(i).id();
            if (!trackIds.add(id)) {
                throw new InvalidRow(true, i, "Duplicate track ID '" + id + "'");
            }
        }
        Set<UserTrack> seen = new HashSet<>();
        for (int i = 0; i < history.size(); i++) {
            Listening row = history.get(i);
            if (!trackIds.contains(row.trackId())) {
                throw new InvalidRow(false, i, "Unknown track ID '" + row.trackId() + "'");
            }
            if (!seen.add(new UserTrack(row.userId(), row.trackId()))) {
                throw new InvalidRow(false, i, "Duplicate history for user '" + row.userId()
                        + "' and track '" + row.trackId() + "'");
            }
        }
    }

    private record UserTrack(String userId, String trackId) {}

    private static final class InvalidRow extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        final boolean inCatalog;
        final int line;

        InvalidRow(boolean inCatalog, int index, String message) {
            super(message);
            this.inCatalog = inCatalog;
            this.line = index + 2; // The first line contains the table header.
        }
    }

    static List<Track> loadCatalog(Path catalog) throws IOException {
        Set<String> ids = new HashSet<>();
        return readTable(catalog, "id\ttitle\tartist\tgenre", fields -> {
            Track track = new Track(fields[0], fields[1], fields[2], fields[3]);
            if (!ids.add(track.id())) {
                throw new IllegalArgumentException("Duplicate track ID '" + track.id() + "'");
            }
            return track;
        });
    }

    public static MusicData load(Path catalog, Path history) throws IOException {
        List<Track> tracks = loadCatalog(catalog);
        List<Listening> events = readTable(history, "user_id\ttrack_id\tplays",
                fields -> new Listening(fields[0], fields[1], parsePlays(fields[2])));
        try {
            return new MusicData(tracks, events);
        } catch (InvalidRow error) {
            Path file = error.inCatalog ? catalog : history;
            throw new IOException(file + ":" + error.line + ": " + error.getMessage(), error);
        }
    }

    /** One play in a chronological evaluation, with the supplied offset resolved to UTC. */
    public record PlayEvent(String userId, String trackId, Instant playedAt) {}

    public static List<PlayEvent> loadEvents(Path events, List<Track> catalog) throws IOException {
        Set<String> trackIds = new HashSet<>();
        for (Track track : catalog) {
            trackIds.add(track.id());
        }
        return List.copyOf(readTable(events, "user_id\ttrack_id\tplayed_at", fields -> {
            if (!trackIds.contains(fields[1])) {
                throw new IllegalArgumentException("Unknown track ID '" + fields[1] + "'");
            }
            try {
                Instant playedAt = OffsetDateTime.parse(fields[2]).toInstant();
                return new PlayEvent(fields[0], fields[1], playedAt);
            } catch (DateTimeParseException error) {
                throw new IllegalArgumentException("Expected ISO-8601 played_at with an explicit UTC offset, found '"
                        + fields[2] + "'", error);
            }
        }));
    }

    /** Reads hidden tracks for one listener from a shared feedback table. */
    static Set<String> loadHiddenTracks(Path feedback, String userId, List<Track> catalog) throws IOException {
        Set<String> hidden = new HashSet<>();
        for (UserTrack row : readFeedback(feedback, catalog)) {
            if (row.userId().equals(userId)) {
                hidden.add(row.trackId());
            }
        }
        return hidden;
    }

    private static List<UserTrack> readFeedback(Path feedback, List<Track> catalog) throws IOException {
        Set<String> trackIds = new HashSet<>();
        for (Track track : catalog) {
            trackIds.add(track.id());
        }
        return readTable(feedback, "user_id\ttrack_id", fields -> {
            if (!trackIds.contains(fields[1])) {
                throw new IllegalArgumentException("Unknown track ID '" + fields[1] + "'");
            }
            return new UserTrack(fields[0], fields[1]);
        });
    }

    static synchronized void hideTrack(Path feedback, String user, String trackId, List<Track> catalog)
            throws IOException {
        if (user.isBlank() || !user.equals(user.strip()) || user.chars().anyMatch(c -> c == '\t' || c == '\n' || c == '\r')) {
            throw new IllegalArgumentException("Listener ID must fit in one feedback field");
        }
        if (catalog.stream().noneMatch(track -> track.id().equals(trackId))) {
            throw new IllegalArgumentException("Unknown track ID '" + trackId + "'");
        }
        Path target = feedback.toAbsolutePath();
        if (Files.isSymbolicLink(target)) target = target.toRealPath();
        Path lock = target.resolveSibling(target.getFileName() + ".lock");
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            List<UserTrack> rows;
            try {
                rows = readFeedback(target, catalog);
            } catch (NoSuchFileException absent) {
                rows = new ArrayList<>();
            }
            UserTrack entry = new UserTrack(user, trackId);
            if (rows.contains(entry)) return;
            rows.add(entry);
            Path temp = Files.createTempFile(target.getParent(), ".feedback-", ".tmp");
            try {
                try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    writer.write("user_id\ttrack_id\n");
                    for (UserTrack row : rows) {
                        writer.write(row.userId() + "\t" + row.trackId() + "\n");
                    }
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static int parsePlays(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Expected a positive integer for plays, found '" + value + "'", error);
        }
    }

    private static <T> List<T> readTable(Path path, String header, Function<String[], T> parseRow)
            throws IOException {
        String[] columns = header.split("\t");
        List<T> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (!header.equals(firstLine) && !("\uFEFF" + header).equals(firstLine)) {
                throw new IOException(path + ":1: Expected tab-separated header " + String.join(", ", columns));
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String[] fields = line.split("\t", -1);
                try {
                    if (fields.length != columns.length) {
                        throw new IllegalArgumentException("Expected " + columns.length + " tab-separated fields");
                    }
                    for (int i = 0; i < fields.length; i++) {
                        fields[i] = fields[i].strip();
                        if (fields[i].isEmpty()) {
                            throw new IllegalArgumentException("Missing " + columns[i]);
                        }
                    }
                    rows.add(parseRow.apply(fields));
                } catch (IllegalArgumentException error) {
                    throw new IOException(path + ":" + lineNumber + ": " + error.getMessage(), error);
                }
            }
        }
        return rows;
    }
}
