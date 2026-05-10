package io.dscope.camel.agent.performance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import ch.qos.logback.classic.Level;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.runtime.AsyncEventPersistenceFacade;
import io.dscope.camel.agent.testing.TestArtifactSupport;
import io.dscope.camel.agent.testing.TestLogCaptureSupport;

class AsyncEventPersistenceFacadePerformanceSmokeTest {

    @Test
    void shouldCapturePersistenceLayerPerformanceAndLogs() throws Exception {
        InMemoryPersistenceFacade delegate = new InMemoryPersistenceFacade();
        List<Long> samplesNanos = new ArrayList<>();
        String conversationId = "perf-persistence";

        try (TestLogCaptureSupport logs = TestLogCaptureSupport.capture(AsyncEventPersistenceFacade.class, Level.INFO);
             AsyncEventPersistenceFacade facade = new AsyncEventPersistenceFacade(delegate, "perf", 64, 10L, 1000L, 1L)) {

            for (int i = 0; i < 5; i++) {
                facade.appendEvent(event(conversationId, "warmup-" + i), "warmup-" + i);
            }
            awaitPersisted(delegate, conversationId, 5);

            for (int i = 0; i < 25; i++) {
                long started = System.nanoTime();
                facade.appendEvent(event(conversationId, "message-" + i), "perf-" + i);
                awaitPersisted(delegate, conversationId, 6 + i);
                samplesNanos.add(System.nanoTime() - started);
            }

            TestArtifactSupport.ArtifactBundle artifacts = TestArtifactSupport.bundle(getClass(), "async-event-persistence");
            TestArtifactSupport.PerformanceSummary summary = TestArtifactSupport.summarize("persistence", samplesNanos);
            artifacts.writeJson("performance-report.json", Map.of(
                "capturedAt", Instant.now().toString(),
                "layer", "persistence",
                "scenario", "async-event-persistence-facade",
                "iterations", samplesNanos.size(),
                "samplesNanos", samplesNanos,
                "summary", summary
            ));
            artifacts.writeJson("persisted-events.json", Map.of(
                "conversationId", conversationId,
                "persistedEventCount", delegate.loadConversation(conversationId, 100).size()
            ));
            logs.writeMessages(
                artifacts,
                "async-persistence.log",
                "Filtered AsyncEventPersistenceFacade log messages",
                "Async audit metrics",
                "Async audit queue full",
                "Async audit persist retry scheduled"
            );
            artifacts.writeIndex("Persistence-layer performance smoke test artifacts");

            Assertions.assertEquals(25, summary.iterations());
            Assertions.assertTrue(summary.minNanos() > 0L, "Measured durations must be positive");
            Assertions.assertTrue(delegate.loadConversation(conversationId, 100).size() >= 30, "Expected warmup and measured events to persist");
        }
    }

    private static AgentEvent event(String conversationId, String value) {
        return new AgentEvent(
            conversationId,
            null,
            "user.message",
            JsonNodeFactory.instance.textNode(value),
            Instant.now()
        );
    }

    private static void awaitPersisted(InMemoryPersistenceFacade delegate, String conversationId, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (delegate.loadConversation(conversationId, expected + 5).size() >= expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        Assertions.fail("Timed out waiting for async persistence to flush " + expected + " events");
    }
}