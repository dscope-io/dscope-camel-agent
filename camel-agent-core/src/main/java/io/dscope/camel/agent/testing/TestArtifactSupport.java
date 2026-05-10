package io.dscope.camel.agent.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class TestArtifactSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private TestArtifactSupport() {
    }

    public static ArtifactBundle bundle(Class<?> owner, String scenario) throws IOException {
        Path directory = Path.of(
            "target",
            "test-debug-artifacts",
            sanitize(owner.getSimpleName()),
            sanitize(scenario)
        );
        Files.createDirectories(directory);
        return new ArtifactBundle(owner.getSimpleName(), scenario, directory);
    }

    public static PerformanceSummary summarize(String layer, List<Long> samplesNanos) {
        if (samplesNanos == null || samplesNanos.isEmpty()) {
            throw new IllegalArgumentException("samplesNanos must not be empty");
        }
        List<Long> sorted = new ArrayList<>(samplesNanos);
        sorted.sort(Comparator.naturalOrder());
        long min = sorted.getFirst();
        long max = sorted.getLast();
        long total = 0L;
        for (Long sample : sorted) {
            total += sample;
        }
        return new PerformanceSummary(
            layer,
            sorted.size(),
            min,
            max,
            total / sorted.size(),
            percentile(sorted, 50),
            percentile(sorted, 95)
        );
    }

    private static long percentile(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil((percentile / 100.0d) * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]+", "-");
    }

    public record ArtifactReference(String name, String path, String kind, String note) {
    }

    public record PerformanceSummary(
        String layer,
        int iterations,
        long minNanos,
        long maxNanos,
        long averageNanos,
        long medianNanos,
        long p95Nanos
    ) {
    }

    public static final class ArtifactBundle {

        private final String owner;
        private final String scenario;
        private final Path directory;
        private final List<ArtifactReference> references = new ArrayList<>();

        private ArtifactBundle(String owner, String scenario, Path directory) {
            this.owner = owner;
            this.scenario = scenario;
            this.directory = directory;
        }

        public Path directory() {
            return directory;
        }

        public Path writeJson(String fileName, Object value) throws IOException {
            Path target = directory.resolve(fileName);
            MAPPER.writeValue(target.toFile(), value);
            references.add(new ArtifactReference(fileName, target.toString(), "json", "Generated JSON artifact"));
            return target;
        }

        public Path writeText(String fileName, String value) throws IOException {
            Path target = directory.resolve(fileName);
            Files.writeString(target, value == null ? "" : value, StandardCharsets.UTF_8);
            references.add(new ArtifactReference(fileName, target.toString(), "text", "Generated text artifact"));
            return target;
        }

        public Path writeLines(String fileName, List<String> lines, String note) throws IOException {
            String content = lines == null ? "" : lines.stream().collect(Collectors.joining(System.lineSeparator()));
            Path target = directory.resolve(fileName);
            Files.writeString(target, content, StandardCharsets.UTF_8);
            references.add(new ArtifactReference(fileName, target.toString(), "log", note == null ? "Generated line-based artifact" : note));
            return target;
        }

        public void addReference(String name, Path path, String kind, String note) {
            references.add(new ArtifactReference(name, path.toString(), kind, note));
        }

        public Path writeIndex(String summary) throws IOException {
            Path target = directory.resolve("artifact-index.json");
            MAPPER.writeValue(target.toFile(), Map.of(
                "capturedAt", Instant.now().toString(),
                "owner", owner,
                "scenario", scenario,
                "summary", summary,
                "artifacts", references
            ));
            return target;
        }
    }
}