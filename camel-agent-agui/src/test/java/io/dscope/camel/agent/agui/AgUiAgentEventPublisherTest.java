package io.dscope.camel.agent.agui;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.agui.bridge.AgUiTaskEventBridge;
import io.dscope.camel.agent.agui.bridge.AgUiToolEventBridge;
import io.dscope.camel.agent.model.AgentEvent;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgUiAgentEventPublisherTest {

    @Test
    void shouldPublishToolEventsViaBridge() {
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean result = new AtomicBoolean(false);

        AgUiToolEventBridge toolBridge = new AgUiToolEventBridge() {
            @Override
            public void onToolCallStart(String runId, String sessionId, String toolName, Map<String, Object> args) {
                started.set(true);
            }

            @Override
            public void onToolCallResult(String runId, String sessionId, String toolName, Object value) {
                result.set(true);
            }
        };

        AgUiTaskEventBridge taskBridge = new AgUiTaskEventBridge() {
        };
        AgUiCorrelationRegistry registry = new AgUiCorrelationRegistry();
        registry.bind("c1", "r1", "s1");

        AgUiAgentEventPublisher publisher = new AgUiAgentEventPublisher(toolBridge, taskBridge, registry);
        ObjectMapper mapper = new ObjectMapper();

        publisher.publish(new AgentEvent("c1", null, "tool.start", mapper.createObjectNode().put("k", "v"), Instant.now()));
        publisher.publish(new AgentEvent("c1", null, "tool.result", mapper.createObjectNode().put("k", "v"), Instant.now()));

        Assertions.assertTrue(started.get());
        Assertions.assertTrue(result.get());
    }
}
