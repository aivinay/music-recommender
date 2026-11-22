package music;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import music.MusicData.PlayEvent;

public final class DiscoverySplitTest {
    public static void main(String[] args) throws Exception {
        var catalog = MusicData.load(Path.of("examples/tracks.tsv"), Path.of("examples/listens.tsv")).tracks();
        var events = new ArrayList<>(MusicData.loadEvents(Path.of("examples/chronological-plays.tsv"), catalog));
        var cutoff = Instant.parse("2026-01-01T00:00:00Z");
        events.add(new PlayEvent("maya", "t01", cutoff.minusSeconds(1)));
        events.add(new PlayEvent("maya", "t02", cutoff.plusSeconds(1)));
        var expected = new DiscoverySplit(List.of(new Listening("lee", "t04", 1),
                new Listening("maya", "t01", 2)), Map.of("maya", "t03"), 1, 1);
        check(expected, DiscoverySplit.from(events, cutoff));
        Collections.reverse(events);
        check(expected, DiscoverySplit.from(events, cutoff));

        events.add(new PlayEvent("newcomer", "t03", cutoff.plusSeconds(2)));
        check(expected, DiscoverySplit.from(events, cutoff));
        check(new DiscoverySplit(List.of(), Map.of(), 3, 0), DiscoverySplit.from(events, Instant.MIN));
    }

    private static void check(DiscoverySplit expected, DiscoverySplit actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
