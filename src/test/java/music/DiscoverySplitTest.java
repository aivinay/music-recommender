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
        // Maya's target t03 ties for first on artist and genre, winning by ID.
        // Popularity ranks t04 first, then the unplayed t02 and t03 by ID.
        var expectedReport = new Evaluator.DiscoveryReport(
                new Evaluator.Report(1, 1, 1, 1, 1.0 / 3), 1, 1);
        checkReport(expectedReport, Evaluator.evaluateDiscovery(catalog, events, cutoff, 3));
        Collections.reverse(events);
        check(expected, DiscoverySplit.from(events, cutoff));

        events.add(new PlayEvent("newcomer", "t03", cutoff.plusSeconds(2)));
        check(expected, DiscoverySplit.from(events, cutoff));
        // These future plays would give a neighbor a shared track and inflate t14's popularity.
        events.add(new PlayEvent("newcomer", "t01", cutoff.plusSeconds(3)));
        for (int i = 0; i < 10; i++) {
            events.add(new PlayEvent("newcomer", "t14", cutoff.plusSeconds(4 + i)));
        }
        checkReport(expectedReport, Evaluator.evaluateDiscovery(catalog, events, cutoff, 3));
        checkReport(new Evaluator.DiscoveryReport(new Evaluator.Report(0, 0, 0, 0, 0), 3, 0),
                Evaluator.evaluateDiscovery(catalog, events, Instant.MIN, 3));
        check(new DiscoverySplit(List.of(), Map.of(), 3, 0), DiscoverySplit.from(events, Instant.MIN));
    }

    private static void checkReport(Evaluator.DiscoveryReport expected, Evaluator.DiscoveryReport actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static void check(DiscoverySplit expected, DiscoverySplit actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
