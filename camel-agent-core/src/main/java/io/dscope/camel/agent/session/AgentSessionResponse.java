package io.dscope.camel.agent.session;

import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.TaskState;
import java.util.List;
import java.util.Map;

public record AgentSessionResponse(
    String conversationId,
    String sessionId,
    boolean created,
    String message,
    List<AgentEvent> events,
    TaskState taskState,
    String resolvedPlanName,
    String resolvedPlanVersion,
    String resolvedBlueprint,
    Map<String, Object> params
) {

    public AgentSessionResponse {
        events = events == null ? List.of() : List.copyOf(events);
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}