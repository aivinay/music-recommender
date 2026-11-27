package music;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ConcurrentFeedbackTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("concurrent-feedback");
        Path feedback = dir.resolve("feedback.tsv");
        var writers = new ArrayList<Process>();
        String header = "user_id\ttrack_id";
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        try {
            Files.writeString(feedback, header + "\nexisting\tt01\n");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (String listener : List.of("maya", "theo", "maya", "lee", "sam", "theo")) {
                writers.add(new ProcessBuilder(java, "-jar", "build/music-recommender.jar",
                        "hide", listener, "t03", "--feedback", feedback.toString()).inheritIO().start());
            }
            for (Process writer : writers) {
                if (!writer.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                    throw new AssertionError("Timed out waiting for feedback writer " + writer.pid());
                }
                if (writer.exitValue() != 0) {
                    throw new AssertionError("Feedback writer " + writer.pid() + " exited " + writer.exitValue());
                }
            }
            var lines = Files.readAllLines(feedback);
            var expected = List.of("existing\tt01", "lee\tt03", "maya\tt03", "sam\tt03", "theo\tt03");
            if (lines.isEmpty() || !header.equals(lines.get(0))
                    || !expected.equals(lines.stream().skip(1).sorted().toList())) {
                throw new AssertionError("Unexpected shared feedback rows: " + lines);
            }
        } finally {
            for (Process writer : writers) {
                if (writer.isAlive()) writer.destroyForcibly();
            }
            for (Process writer : writers) {
                if (!writer.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("Writer survived cleanup");
            }
            try (var entries = Files.list(dir)) {
                for (Path entry : entries.toList()) Files.delete(entry);
            }
            Files.delete(dir);
        }
    }
}
