package io.dscope.camel.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record AgentEvent(
    String conversationId,
    String taskId,
    String type,
    JsonNode payload,
    Instant timestamp
) {
}
