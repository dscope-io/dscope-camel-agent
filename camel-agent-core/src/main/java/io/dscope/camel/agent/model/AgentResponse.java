package io.dscope.camel.agent.model;

import java.util.List;

public record AgentResponse(
    String conversationId,
    String message,
    List<AgentEvent> events,
    TaskState taskState
) {
}
