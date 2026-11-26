package music;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/** Evaluates personalized and popularity rankings against held-out tracks. */
public final class Evaluator {
    private Evaluator() {}

    public record Report(int listeners, double hitRate, double meanReciprocalRank,
                         double popularityHitRate, double popularityMeanReciprocalRank) {}

    public record DiscoveryReport(Report rankings, int missingTraining, int missingDiscovery) {}

    public static DiscoveryReport evaluateDiscovery(List<Track> tracks, List<MusicData.PlayEvent> events,
                                                    Instant cutoff, int limit) {
        requirePositiveLimit(limit);
        Set<String> trackIds = new HashSet<>();
        for (Track track : tracks) trackIds.add(track.id());
        for (MusicData.PlayEvent event : events) {
            if (!trackIds.contains(event.trackId())) {
                throw new IllegalArgumentException("Unknown track ID '" + event.trackId() + "'");
            }
        }
        DiscoverySplit split = DiscoverySplit.from(events, cutoff);
        Report rankings = score(new MusicData(tracks, split.training()), split.targets(), limit);
        return new DiscoveryReport(rankings, split.missingTraining(), split.missingDiscovery());
    }

    public static Report evaluate(MusicData data, int limit) {
        requirePositiveLimit(limit);
        Map<String, String> heldOut = selectHoldouts(data.history());
        List<Listening> training = data.history().stream()
                .filter(row -> !row.trackId().equals(heldOut.get(row.userId())))
                .toList();
        return score(new MusicData(data.tracks(), training), heldOut, limit);
    }

    private static void requirePositiveLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static Report score(MusicData training, Map<String, String> heldOut, int limit) {
        int listeners = heldOut.size();
        if (listeners == 0) {
            return new Report(0, 0, 0, 0, 0);
        }
        Map<String, Set<String>> heard = new HashMap<>();
        Map<String, Long> playCounts = new HashMap<>();
        for (Listening row : training.history()) {
            heard.computeIfAbsent(row.userId(), key -> new HashSet<>()).add(row.trackId());
            playCounts.merge(row.trackId(), (long) row.plays(), Long::sum);
        }

        Recommender recommender = new Recommender(training);
        List<String> popular = training.tracks().stream().map(Track::id)
                .sorted(Comparator.<String>comparingLong(id -> playCounts.getOrDefault(id, 0L))
                        .reversed().thenComparing(Comparator.naturalOrder()))
                .toList();

        Tally personalized = new Tally();
        Tally baseline = new Tally();
        for (var entry : heldOut.entrySet()) {
            String user = entry.getKey();
            String target = entry.getValue();
            personalized.add(target, recommender.recommend(user, limit).stream()
                    .map(pick -> pick.track().id()).toList());
            Set<String> excluded = heard.get(user);
            baseline.add(target, popular.stream()
                    .filter(id -> !excluded.contains(id)).limit(limit).toList());
        }
        return new Report(listeners, (double) personalized.hits / listeners,
                personalized.reciprocalRanks / listeners, (double) baseline.hits / listeners,
                baseline.reciprocalRanks / listeners);
    }

    private static Map<String, String> selectHoldouts(List<Listening> history) {
        Map<String, List<String>> byUser = new HashMap<>();
        for (Listening row : history) {
            byUser.computeIfAbsent(row.userId(), key -> new ArrayList<>()).add(row.trackId());
        }
        Map<String, String> heldOut = new TreeMap<>();
        for (var entry : byUser.entrySet()) {
            List<String> ids = entry.getValue();
            if (ids.size() >= 2) {
                ids.sort(Comparator.naturalOrder());
                String user = entry.getKey();
                int index = new Random(user.hashCode()).nextInt(ids.size());
                heldOut.put(user, ids.get(index));
            }
        }
        return heldOut;
    }

    private static final class Tally {
        int hits;
        double reciprocalRanks;

        void add(String target, List<String> ranking) {
            int index = ranking.indexOf(target);
            if (index >= 0) {
                hits++;
                reciprocalRanks += 1.0 / (index + 1);
            }
        }
    }
}
