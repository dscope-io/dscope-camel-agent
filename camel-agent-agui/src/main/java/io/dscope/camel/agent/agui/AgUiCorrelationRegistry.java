package io.dscope.camel.agent.agui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AgUiCorrelationRegistry {

    private final Map<String, String> conversationToRun = new ConcurrentHashMap<>();
    private final Map<String, String> conversationToSession = new ConcurrentHashMap<>();

    public void bind(String conversationId, String runId, String sessionId) {
        conversationToRun.put(conversationId, runId);
        conversationToSession.put(conversationId, sessionId);
    }

    public String runId(String conversationId) {
        return conversationToRun.getOrDefault(conversationId, conversationId);
    }

    public String sessionId(String conversationId) {
        return conversationToSession.getOrDefault(conversationId, conversationId);
    }
}
