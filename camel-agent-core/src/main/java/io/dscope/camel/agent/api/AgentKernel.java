package io.dscope.camel.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.dscope.camel.agent.model.AgentResponse;

public interface AgentKernel {

    AgentResponse handleUserMessage(String conversationId, String userMessage);

    AgentResponse resumeTask(String taskId);

    default AgentResponse handleRealtimeEvent(String conversationId, String eventType, JsonNode payload) {
        throw new UnsupportedOperationException("Realtime event handling is not configured");
    }
}
