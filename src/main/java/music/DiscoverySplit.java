package music;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import music.MusicData.PlayEvent;

/** Training plays and next-discovery targets at a single global cutoff. */
record DiscoverySplit(List<Listening> training, Map<String, String> targets,
                      int missingTraining, int missingDiscovery) {
    static DiscoverySplit from(List<PlayEvent> events, Instant cutoff) {
        Map<String, Map<String, Integer>> counts = new TreeMap<>();
        Set<String> listeners = new HashSet<>();
        for (PlayEvent event : events) {
            listeners.add(event.userId());
            if (event.playedAt().isBefore(cutoff)) {
                counts.computeIfAbsent(event.userId(), id -> new TreeMap<>())
                        .merge(event.trackId(), 1, Integer::sum);
            }
        }

        Comparator<PlayEvent> byTimeAndTrack = Comparator.comparing(PlayEvent::playedAt)
                .thenComparing(PlayEvent::trackId);
        Map<String, PlayEvent> firstDiscovery = new HashMap<>();
        for (PlayEvent event : events) {
            Map<String, Integer> heard = counts.get(event.userId());
            if (heard != null && !heard.containsKey(event.trackId())) {
                // A track absent from this listener's training history can only occur at or after cutoff.
                firstDiscovery.merge(event.userId(), event,
                        (a, b) -> byTimeAndTrack.compare(a, b) <= 0 ? a : b);
            }
        }

        List<Listening> training = new ArrayList<>();
        counts.forEach((user, tracks) -> tracks.forEach((track, plays) ->
                training.add(new Listening(user, track, plays))));
        Map<String, String> targets = new TreeMap<>();
        firstDiscovery.forEach((user, event) -> targets.put(user, event.trackId()));
        return new DiscoverySplit(List.copyOf(training), Collections.unmodifiableMap(targets),
                listeners.size() - counts.size(), counts.size() - targets.size());
    }
}
