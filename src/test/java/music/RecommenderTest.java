package music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class RecommenderTest {
    private static final Track SEED = new Track("seed", "Night Train", "North", "Jazz");
    private static final Track PEER = new Track("peer", "Daylight", "West", "Soul");
    private static final Track GENRE = new Track("genre", "Skyline", "South", "jazz");
    private static final Track ARTIST = new Track("artist", "Tide", "NORTH", "Pop");
    private static final Track HIT = new Track("hit", "Cloud", "East", "Rock");

    public static void main(String[] args) {
        ranksForNewListeners();
        usesTemporaryFavorites();
        explainsPeerFavorites();
        limitsTracksPerArtist();
        excludesHiddenTracks();
        matchesGenreAndArtist();
        accountsForRepeatListens();
        discoversSongsThroughSimilarListeners();
        handlesEmptyResultsAndInvalidLimits();
        System.out.println("Recommender tests passed");
    }

    private static Recommender model(List<Track> tracks, List<Listening> history) {
        return new Recommender(new MusicData(tracks, history));
    }

    private static void usesTemporaryFavorites() {
        var engine = model(List.of(HIT, SEED, GENRE, ARTIST), List.of(new Listening("fan", "hit", 7)));
        var original = engine.recommend("new", 4);
        var seeded = engine.recommend("new", 4, 4, Set.of(), List.of("seed"));
        equal(List.of("genre", "artist", "hit"), ids(seeded));
        equal("Shares a genre with Night Train", seeded.get(0).reason());
        equal("Shares an artist with Night Train", seeded.get(1).reason());
        equal("Popular across listening history", seeded.get(2).reason());
        equal(seeded, engine.recommend("new", 4, 4, Set.of(), List.of("seed", "seed")));
        var returning = engine.recommend("fan", 4, 4, Set.of(), List.of("hit", "seed"));
        // Seven stored plays contribute three times the log-weight of the single seed play.
        close(0.25 / 4, returning.get(0).score());
        equal(List.of("genre", "artist"), ids(returning));
        equal(original, engine.recommend("new", 4));
        try {
            engine.recommend("fan", 4, 4, Set.of(), List.of("missing"));
            throw new AssertionError("Accepted an unknown seed");
        } catch (IllegalArgumentException expected) {
            equal(true, expected.getMessage().contains("missing"));
        }
    }

    private static void explainsPeerFavorites() {
        var engine = model(List.of(SEED, PEER, HIT), List.of(new Listening("neighbor", "seed", 3),
                new Listening("neighbor", "peer", 3), new Listening("returning", "seed", 1)));
        var fresh = engine.recommend("new", 1, 1, Set.of(), List.of("seed")).get(0);
        equal("peer", fresh.track().id());
        equal("Played by listeners who also played Night Train", fresh.reason());
        var returning = engine.recommend("returning", 1, 1, Set.of(), List.of("hit")).get(0);
        equal("peer", returning.track().id());
        equal("Played by listeners with similar taste", returning.reason());
    }

    private static void ranksForNewListeners() {
        var engine = model(List.of(HIT, GENRE, PEER, SEED), List.of(
                new Listening("a", "seed", 3), new Listening("b", "seed", 4),
                new Listening("a", "hit", 6)));
        var result = engine.recommend("new", 4);
        equal(List.of("seed", "hit", "genre", "peer"), ids(result));
        close(1.0, result.get(0).score());
        close(Math.log1p(6) / Math.log1p(7), result.get(1).score());
        close(0.0, result.get(2).score());
        equal(List.of("seed"), ids(engine.recommend("new", 1)));
    }

    private static void limitsTracksPerArtist() {
        var tracks = List.of(new Track("a", "A", "North", "Jazz"), new Track("b", "B", "NORTH", "Jazz"),
                new Track("c", "C", "north", "Jazz"), new Track("d", "D", "West", "Folk"), SEED);
        var engine = model(tracks, List.of(new Listening("me", "seed", 1),
                new Listening("other", "a", 12), new Listening("other", "b", 10),
                new Listening("other", "c", 8)));
        for (String user : List.of("me", "new")) {
            var ranked = engine.recommend(user, 10);
            var west = ranked.stream().filter(pick -> pick.track().id().equals("d")).findFirst().orElseThrow();
            equal(List.of(ranked.get(0), west), engine.recommend(user, 3, 1));
            equal(List.of(ranked.get(0), ranked.get(1), west), engine.recommend(user, 3, 2));
        }
        for (int cap : List.of(0, -1)) {
            try {
                engine.recommend("me", 3, cap);
                throw new AssertionError("Accepted invalid artist limit " + cap);
            } catch (IllegalArgumentException expected) {
                // Only positive caps are supported.
            }
        }
    }

    private static void excludesHiddenTracks() {
        var engine = model(List.of(ARTIST, GENRE, HIT, SEED), List.of(new Listening("me", "seed", 4)));
        for (String user : List.of("me", "new")) {
            var full = engine.recommend(user, 4);
            String firstId = full.get(0).track().id();
            equal(full.subList(1, 3), engine.recommend(user, 2, 2, Set.of(firstId)));
            equal(List.of(), engine.recommend(user, 2, 1, Set.of("seed", "artist", "genre", "hit")));
            equal(full, engine.recommend(user, 4));
        }
        try {
            engine.recommend("me", 1, 1, Set.of("missing"));
            throw new AssertionError("Accepted an unknown hidden track");
        } catch (IllegalArgumentException expected) {
            equal(true, expected.getMessage().contains("missing"));
        }
    }

    private static void matchesGenreAndArtist() {
        var engine = model(List.of(HIT, ARTIST, SEED, GENRE), List.of(
                new Listening("me", "seed", 7), new Listening("stranger", "hit", 100)));
        var result = engine.recommend("me", 10);
        // The stranger shares no tracks with me, so the hit gets only its popularity score.
        equal(List.of("genre", "artist", "hit"), ids(result));
        close(0.25, result.get(0).score());
        close(0.20, result.get(1).score());
        close(0.10, result.get(2).score());
        equal("Matches a genre you play: jazz", result.get(0).reason());
        equal("More from an artist you play: NORTH", result.get(1).reason());
    }

    private static void accountsForRepeatListens() {
        Track rock = new Track("rock", "Another Cloud", "Coast", "Rock");
        var engine = model(List.of(SEED, HIT, GENRE, rock), List.of(
                new Listening("me", "seed", 7), new Listening("me", "hit", 1)));
        var result = engine.recommend("me", 2);
        // log(1 + 7) is three times log(1 + 1), giving jazz three quarters of the profile.
        equal(List.of("genre", "rock"), ids(result));
        close(0.25 * 0.75, result.get(0).score());
        close(0.25 * 0.25, result.get(1).score());
    }

    private static void discoversSongsThroughSimilarListeners() {
        var tracks = new ArrayList<>(List.of(HIT, GENRE, PEER, SEED));
        var plays = new ArrayList<>(List.of(new Listening("me", "seed", 1),
                new Listening("neighbor", "seed", 10), new Listening("neighbor", "peer", 10),
                new Listening("stranger", "hit", 20)));
        var ranked = model(tracks, plays).recommend("me", 3);
        equal(List.of("peer", "genre", "hit"), ids(ranked));
        equal("Played by listeners with similar taste", ranked.get(0).reason());
        close(0.45 * 0.5 + 0.10 * Math.log1p(10) / Math.log1p(20), ranked.get(0).score());

        Collections.reverse(tracks);
        Collections.reverse(plays);
        equal(ranked, model(tracks, plays).recommend("me", 3));
    }

    private static void handlesEmptyResultsAndInvalidLimits() {
        var emptyHistory = model(List.of(SEED, HIT), List.of());
        var unexplored = emptyHistory.recommend("new", 20);
        equal(List.of("hit", "seed"), ids(unexplored));
        close(0.0, unexplored.get(0).score());
        equal(List.of(), model(List.of(), List.of()).recommend("new", 2));
        equal(List.of(), model(List.of(SEED), List.of(new Listening("me", "seed", 1)))
                .recommend("me", 3));
        try {
            emptyHistory.recommend("new", 0);
            throw new AssertionError("Accepted a zero limit");
        } catch (IllegalArgumentException expected) {
            // The caller must supply a positive limit.
        }
    }

    private static List<String> ids(List<Recommender.Recommendation> recommendations) {
        return recommendations.stream().map(r -> r.track().id()).toList();
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static void close(double expected, double actual) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 1e-9) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
