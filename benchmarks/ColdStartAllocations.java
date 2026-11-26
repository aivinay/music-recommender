import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.stream.IntStream;
import music.MusicData;
import music.Recommender;
import music.Track;

// After make build, run: java -cp build/music-recommender.jar benchmarks/ColdStartAllocations.java
class ColdStartAllocations {
    public static void main(String[] args) {
        var tracks = IntStream.range(0, 10_000)
                .mapToObj(i -> new Track("t" + i, "Title " + i, "Artist", "Genre"))
                .toList();
        var model = new Recommender(new MusicData(tracks, List.of()));
        var meter = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
        if (meter == null || !meter.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("This benchmark requires thread allocation counters");
        }
        meter.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 20; i++) model.recommend("new", 5);

        int queries = 50;
        long start = meter.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < queries; i++) {
            if (model.recommend("new", 5).size() != 5) throw new AssertionError("Expected five tracks");
        }
        long allocated = meter.getCurrentThreadAllocatedBytes() - start;
        System.out.println(allocated / queries + " bytes per five-track newcomer query");
    }
}
