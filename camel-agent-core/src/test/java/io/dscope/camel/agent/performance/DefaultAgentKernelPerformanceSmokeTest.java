package io.dscope.camel.agent.performance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.kernel.DefaultAgentKernel;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.kernel.StaticAiModelClient;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.registry.DefaultToolRegistry;
import io.dscope.camel.agent.testing.TestArtifactSupport;
import io.dscope.camel.agent.validation.SchemaValidator;

class DefaultAgentKernelPerformanceSmokeTest {

    @Test
    void shouldCaptureKernelLayerPerformanceReport() throws Exception {
        ToolSpec toolSpec = new ToolSpec("echo", "echo", "echo", null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = new AgentBlueprint("perf-demo", "0.1", "system", List.of(toolSpec), List.of());
        ToolExecutor noOpExecutor = (tool, args, ctx) -> new ToolResult("ok", new ObjectMapper().createObjectNode(), List.of());

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            noOpExecutor,
            new StaticAiModelClient(),
            new InMemoryPersistenceFacade(),
            new SchemaValidator(),
            new ObjectMapper()
        );

        for (int i = 0; i < 5; i++) {
            kernel.handleUserMessage("perf-kernel-warmup-" + i, "hello warmup");
        }

        List<Long> samplesNanos = new ArrayList<>();
        String lastMessage = "";
        for (int i = 0; i < 25; i++) {
            long started = System.nanoTime();
            var response = kernel.handleUserMessage("perf-kernel-" + i, "hello performance");
            samplesNanos.add(System.nanoTime() - started);
            lastMessage = response.message();
            Assertions.assertFalse(response.message().isBlank(), "Kernel response should be present during performance smoke test");
        }

        TestArtifactSupport.ArtifactBundle artifacts = TestArtifactSupport.bundle(getClass(), "default-agent-kernel");
        TestArtifactSupport.PerformanceSummary summary = TestArtifactSupport.summarize("kernel", samplesNanos);
        artifacts.writeJson("performance-report.json", Map.of(
            "capturedAt", Instant.now().toString(),
            "layer", "kernel",
            "scenario", "default-agent-kernel-no-tool",
            "iterations", samplesNanos.size(),
            "samplesNanos", samplesNanos,
            "summary", summary
        ));
        artifacts.writeText("last-response.txt", lastMessage);
        artifacts.writeIndex("Kernel-layer performance smoke test artifacts");

        Assertions.assertEquals(25, summary.iterations());
        Assertions.assertTrue(summary.minNanos() > 0L, "Measured durations must be positive");
    }
}