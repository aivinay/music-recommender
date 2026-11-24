# Music Recommender

A Java 17 library and command-line app that recommends songs a listener has yet to play. It combines listening habits, artist and genre preferences, and the tastes of similar listeners. Every result includes a score and a reason.

## Run the demo

You need a JDK (17 or newer) and Make. Run these commands from the repository root. The build uses the JDK alone, and the demo includes 14 fictional songs and six listeners.

```sh
make build
java -jar build/music-recommender.jar recommend maya --limit 5
```

Maya has played two breakbeat songs. Her top pick is **Last Train in Violet**, another track by Copper Meridian. She also gets **Paper Constellation**, a jazz track played by people who share her taste in breakbeat. **Dawn at Switchpoint** makes the list through its artist and genre even though it has zero plays.

```sh
# See how the ranking changes for a first-time listener
java -jar build/music-recommender.jar recommend newcomer --limit 3

# Evaluate against a popularity baseline
java -jar build/music-recommender.jar evaluate --limit 3

# Build the JAR and run the test suite
make test
```

## Request recommendations over HTTP

From the repository root, start the server with the example dataset. Port `0` lets the operating system choose an available loopback port.

```sh
make build
java -jar build/music-recommender.jar serve --port 0
```

In a second terminal, paste the URL printed after `Listening on` at the prompt. This request returns Maya’s top unheard track.

```sh
printf 'Server URL: '
read -r base_url
curl -fsS "${base_url}/recommendations?user=maya&limit=1"
```

```json
{"recommendations":[{"id":"t03","title":"Last Train in Violet","artist":"Copper Meridian","genre":"Breakbeat","score":0.5058485492490495,"explanation":"Matches a genre you play: Breakbeat"}]}
```

## Supply your own music

Pass a track catalog and aggregated play counts as UTF-8 tab-separated files. The defaults are [examples/tracks.tsv](examples/tracks.tsv) and [examples/listens.tsv](examples/listens.tsv), relative to the working directory.

| File | Header fields, in order | Each row represents |
| --- | --- | --- |
| Tracks | `id`, `title`, `artist`, `genre` | One song with a unique ID |
| Listens | `user_id`, `track_id`, `plays` | One listener's total plays of one catalog track |

Use the exact headers with a tab between fields. Every field is required. Counts must be positive 32-bit integers. IDs are case-sensitive. A listening file containing only the header is valid.

```sh
java -jar build/music-recommender.jar recommend listener-42 \
  --tracks /path/to/tracks.tsv --listens /path/to/listens.tsv --limit 10
```

To personalize a first visit, run `java -jar build/music-recommender.jar recommend newcomer --seed-track t01 --limit 5`. On the demo catalog, choosing *Platform After Rain* moves *Last Train in Violet* ahead of the popularity leader, *Slow Hours Market*. Repeat `--seed-track` to add more favorites. Each distinct favorite counts once for this request unless the listener already has plays for it, in which case their stored count applies.

To request at most one track per artist, run `java -jar build/music-recommender.jar recommend maya --limit 5 --max-per-artist 1`. The cap applies to personalized and popularity rankings, matches artist names case-insensitively, and can yield a shorter list when too few artists qualify. Java callers can use `model.recommend("maya", 5, 1)`.

### Hide tracks using a feedback file

Pass `--feedback FILE` to `recommend` to skip tracks for a particular listener. The UTF-8 TSV has two columns, `user_id` and `track_id`, with one hidden pair per row. From the repository root, try:

```sh
java -jar build/music-recommender.jar hide maya t04 --feedback example-feedback.tsv
java -jar build/music-recommender.jar recommend maya --feedback example-feedback.tsv
```

Maya's list skips *Paper Constellation* and fills the slot with the next eligible track. Other listeners can still receive it. Run `unhide maya t04 --feedback example-feedback.tsv` with the same JAR to restore the track. Its rank still depends on Maya’s listening history. Repeating either command is safe.

## Ranking

The engine excludes tracks the listener has already played. For each remaining track it computes a weighted sum. The explanation names the largest contribution.

| Weight | Signal | Calculation |
| ---: | --- | --- |
| 45% | Similar listeners | Cosine similarity on log-weighted play counts. Each neighbor contributes its similarity times its normalized weight for the candidate. Divide the sum by the total similarity, with a minimum divisor of one. |
| 25% | Genre | Share of the listener's log-weighted plays in the track's genre |
| 20% | Artist | Share of the listener's log-weighted plays for the track's artist |
| 10% | Popularity | Log of one plus total track plays, divided by the same value for the most-played track |

Log weighting uses `log(1 + plays)` to soften the influence of repeated plays. Artist and genre matching ignore case. A new listener gets the popularity ranking with ties broken by track ID. With an empty history every score is zero. The model runs in memory and scans listening history and the catalog for each personalized request.

## Evaluate recommendations

`evaluate --limit K` hides one track per listener who has played at least two distinct tracks. It sorts each listener's track IDs and uses the listener ID to seed a reproducible choice. Both rankings use the same training data with all held-out plays removed. Both exclude tracks the listener still has in training.

**HitRate@K** is the fraction of eligible listeners whose hidden track appears in their top K. **MRR@K** averages the reciprocal of its rank, counting a miss as zero. This split measures recovery of a known interest.

To evaluate the next new track each listener discovers, use a UTF-8 TSV with one play per row and columns `user_id`, `track_id`, and `played_at`. Both timestamps and the cutoff take an explicit UTC offset.

```sh
java -jar build/music-recommender.jar evaluate --events examples/chronological-plays.tsv --cutoff 2026-01-01T00:00:00Z --limit 3
```

Both rankings train on plays strictly before the cutoff. In this example, Maya discovers two tracks at the cutoff. Track ID breaks the tie, making `t03` her target. Personalization ranks it first and popularity ranks it third, giving both a HitRate@3 of 1 and MRR@3 values of 1 and 1/3 respectively. Lee only repeats a track and the newcomer has no training plays, so both are skipped.

## Use from Java

Add `build/music-recommender.jar` to your classpath. A single model can serve multiple listeners from the same snapshot.

```java
import java.nio.file.Path;
import music.MusicData;
import music.Recommender;

// Inside a method that can throw IOException:
var data = MusicData.load(Path.of("examples/tracks.tsv"), Path.of("examples/listens.tsv"));
var model = new Recommender(data);

for (var pick : model.recommend("maya", 5)) {
    System.out.println(pick.track().title() + " — " + pick.reason());
}
```
