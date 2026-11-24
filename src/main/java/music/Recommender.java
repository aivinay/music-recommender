package music;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** An in-memory recommender for a snapshot of a music catalog and listening history. */
public final class Recommender {
    public record Recommendation(Track track, double score, String reason) {}

    private static final double PEER_WEIGHT = 0.45;
    private static final double GENRE_WEIGHT = 0.25;
    private static final double ARTIST_WEIGHT = 0.20;
    private static final double POPULARITY_WEIGHT = 0.10;

    private final Map<String, Track> catalog = new TreeMap<>();
    private final Map<String, Map<String, Double>> listeners = new TreeMap<>();
    private final Map<String, Double> profileNorms = new HashMap<>();
    private final Map<String, Double> popularity = new HashMap<>();

    public Recommender(MusicData data) {
        for (Track track : data.tracks()) {
            catalog.put(track.id(), track);
        }

        Map<String, Long> totalPlays = new HashMap<>();
        for (Listening row : data.history()) {
            Map<String, Double> vector = listeners.computeIfAbsent(row.userId(), id -> new TreeMap<>());
            vector.put(row.trackId(), Math.log1p(row.plays()));
            totalPlays.merge(row.trackId(), (long) row.plays(), Long::sum);
        }

        // Normalizing once makes each dot product a cosine similarity at query time.
        for (var listener : listeners.entrySet()) {
            var vector = listener.getValue();
            double norm = Math.sqrt(vector.values().stream().mapToDouble(w -> w * w).sum());
            profileNorms.put(listener.getKey(), norm);
            vector.replaceAll((id, weight) -> weight / norm);
        }
        long maxPlays = totalPlays.values().stream().mapToLong(Long::longValue).max().orElse(0);
        totalPlays.forEach((id, total) -> popularity.put(id, Math.log1p(total) / Math.log1p(maxPlays)));
    }

    /** Returns at most limit unheard tracks, in descending score order with ties broken by ID. */
    public List<Recommendation> recommend(String userId, int limit) {
        return recommend(userId, limit, Integer.MAX_VALUE);
    }

    /** Keeps at most maxPerArtist tracks per artist while preserving their ranking and scores. */
    public List<Recommendation> recommend(String userId, int limit, int maxPerArtist) {
        return recommend(userId, limit, maxPerArtist, Set.of());
    }

    /** Excludes the requesting listener's hidden tracks before filling the result list. */
    public List<Recommendation> recommend(String userId, int limit, int maxPerArtist, Set<String> hiddenTrackIds) {
        return recommend(userId, limit, maxPerArtist, hiddenTrackIds, List.of());
    }

    /** Gives each distinct unheard favorite one temporary play for this request. */
    public List<Recommendation> recommend(String userId, int limit, int maxPerArtist,
                                          Set<String> hiddenTrackIds, List<String> seedTrackIds) {
        for (String id : hiddenTrackIds) {
            if (id == null || !catalog.containsKey(id)) {
                throw new IllegalArgumentException("Unknown hidden track ID '" + id + "'");
            }
        }
        if (maxPerArtist < 1) {
            throw new IllegalArgumentException("Maximum tracks per artist must be at least 1");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("Limit must be at least 1");
        }

        Map<String, Double> heard = profileWithSeeds(userId, seedTrackIds);
        Map<String, Double> artists = new HashMap<>();
        Map<String, Double> genres = new HashMap<>();
        Set<String> favorites = Set.copyOf(seedTrackIds);
        Map<String, Track> artistSources = new HashMap<>();
        Map<String, Track> genreSources = new HashMap<>();
        double weightSum = heard.values().stream().mapToDouble(Double::doubleValue).sum();
        heard.forEach((id, weight) -> {
            Track track = catalog.get(id);
            if (!favorites.isEmpty()) {
                // Profile order is by track ID, so equally weighted sources have a stable tie break.
                artistSources.merge(key(track.artist()), track,
                        (a, b) -> heard.get(a.id()) >= heard.get(b.id()) ? a : b);
                genreSources.merge(key(track.genre()), track,
                        (a, b) -> heard.get(a.id()) >= heard.get(b.id()) ? a : b);
            }
            artists.merge(key(track.artist()), weight / weightSum, Double::sum);
            genres.merge(key(track.genre()), weight / weightSum, Double::sum);
        });

        Map<String, Double> peers = heard.isEmpty() ? Map.of() : peerSupport(userId, heard);
        List<Recommendation> candidates = new ArrayList<>();
        for (Track track : catalog.values()) {
            if (heard.containsKey(track.id()) || hiddenTrackIds.contains(track.id())) {
                continue;
            }
            double popular = popularity.getOrDefault(track.id(), 0.0);
            if (heard.isEmpty()) {
                candidates.add(new Recommendation(track, popular, popularityReason(popular)));
            } else {
                double peer = PEER_WEIGHT * peers.getOrDefault(track.id(), 0.0);
                double genre = GENRE_WEIGHT * genres.getOrDefault(key(track.genre()), 0.0);
                double artist = ARTIST_WEIGHT * artists.getOrDefault(key(track.artist()), 0.0);
                double baseline = POPULARITY_WEIGHT * popular;
                String reason = strongestReason(track, peer, genre, artist, baseline, favorites,
                        genreSources.get(key(track.genre())), artistSources.get(key(track.artist())));
                candidates.add(new Recommendation(track, peer + genre + artist + baseline, reason));
            }
        }
        candidates.sort(Comparator.comparingDouble(Recommendation::score).reversed()
                .thenComparing(r -> r.track().id()));
        if (maxPerArtist >= limit) {
            return List.copyOf(candidates.subList(0, Math.min(limit, candidates.size())));
        }
        Map<String, Integer> selectedByArtist = new HashMap<>();
        List<Recommendation> selected = new ArrayList<>();
        for (Recommendation candidate : candidates) {
            String artist = key(candidate.track().artist());
            int count = selectedByArtist.getOrDefault(artist, 0);
            if (count < maxPerArtist) {
                selectedByArtist.put(artist, count + 1);
                selected.add(candidate);
                if (selected.size() == limit) break;
            }
        }
        return List.copyOf(selected);
    }

    private Map<String, Double> profileWithSeeds(String userId, List<String> seeds) {
        Map<String, Double> history = listeners.getOrDefault(userId, Map.of());
        if (seeds.isEmpty()) return history;

        Map<String, Double> profile = new TreeMap<>(history);
        // Put the one-play weight on the same scale as the normalized history.
        double seedWeight = Math.log1p(1) / profileNorms.getOrDefault(userId, 1.0);
        for (String id : seeds) {
            if (id == null || !catalog.containsKey(id)) {
                throw new IllegalArgumentException("Unknown seed track ID '" + id + "'");
            }
            profile.putIfAbsent(id, seedWeight);
        }
        if (profile.size() == history.size()) return history;
        double norm = Math.sqrt(profile.values().stream().mapToDouble(w -> w * w).sum());
        profile.replaceAll((id, weight) -> weight / norm);
        return profile;
    }

    private Map<String, Double> peerSupport(String userId, Map<String, Double> heard) {
        Map<String, Double> support = new HashMap<>();
        double similaritySum = 0;
        for (var entry : listeners.entrySet()) {
            if (entry.getKey().equals(userId)) {
                continue;
            }
            Map<String, Double> neighbor = entry.getValue();
            double similarity = 0;
            for (var play : neighbor.entrySet()) {
                similarity += play.getValue() * heard.getOrDefault(play.getKey(), 0.0);
            }
            if (similarity > 0) {
                similaritySum += similarity;
                for (var play : neighbor.entrySet()) {
                    support.merge(play.getKey(), similarity * play.getValue(), Double::sum);
                }
            }
        }
        // A single weak match should contribute less than a strongly matching peer group.
        double denominator = Math.max(1.0, similaritySum);
        support.replaceAll((id, sum) -> sum / denominator);
        return support;
    }

    private static String strongestReason(Track track, double peer, double genre, double artist, double popular,
                                          Set<String> favorites, Track genreSource, Track artistSource) {
        double strongest = Math.max(Math.max(peer, genre), Math.max(artist, popular));
        if (strongest > 0 && strongest == peer) {
            return "Played by listeners with similar taste";
        }
        if (strongest > 0 && strongest == genre) {
            if (genreSource != null && favorites.contains(genreSource.id())) {
                return "Shares a genre with " + genreSource.title();
            }
            return "Matches a genre you play: " + track.genre();
        }
        if (strongest > 0 && strongest == artist) {
            if (artistSource != null && favorites.contains(artistSource.id())) {
                return "Shares an artist with " + artistSource.title();
            }
            return "More from an artist you play: " + track.artist();
        }
        return popularityReason(popular);
    }

    private static String popularityReason(double popular) {
        return popular > 0 ? "Popular across listening history" : "Explore an unplayed catalog track";
    }

    private static String key(String label) {
        return label.toLowerCase(Locale.ROOT);
    }
}
