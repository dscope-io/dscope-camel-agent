package io.dscope.camel.agent.session;

import java.util.Map;

public record AgentSessionRequest(
    String prompt,
    String conversationId,
    String sessionId,
    String threadId,
    String planName,
    String planVersion,
    Map<String, Object> params
) {

    public AgentSessionRequest {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}