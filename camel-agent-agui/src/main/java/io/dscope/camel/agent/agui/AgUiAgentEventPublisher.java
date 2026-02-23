package io.dscope.camel.agent.agui;

import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.agui.bridge.AgUiTaskEventBridge;
import io.dscope.camel.agent.agui.bridge.AgUiToolEventBridge;
import java.util.Map;

public class AgUiAgentEventPublisher {

    private final AgUiToolEventBridge toolBridge;
    private final AgUiTaskEventBridge taskBridge;
    private final AgUiCorrelationRegistry correlationRegistry;

    public AgUiAgentEventPublisher(AgUiToolEventBridge toolBridge,
                                   AgUiTaskEventBridge taskBridge,
                                   AgUiCorrelationRegistry correlationRegistry) {
        this.toolBridge = toolBridge;
        this.taskBridge = taskBridge;
        this.correlationRegistry = correlationRegistry;
    }

    public void publish(AgentEvent event) {
        String runId = correlationRegistry.runId(event.conversationId());
        String sessionId = correlationRegistry.sessionId(event.conversationId());

        switch (event.type()) {
            case "tool.start" -> toolBridge.onToolCallStart(runId, sessionId, "tool", Map.of("payload", event.payload()));
            case "tool.result" -> toolBridge.onToolCallResult(runId, sessionId, "tool", event.payload());
            default -> taskBridge.onTaskEvent(runId, sessionId, event.type(), Map.of("payload", event.payload()));
        }
    }
}
