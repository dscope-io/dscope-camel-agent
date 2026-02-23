package io.dscope.camel.agent.agui.bridge;

import java.util.Map;

public interface AgUiToolEventBridge {

    default void onToolCallStart(String runId, String sessionId, String toolName, Map<String, Object> args) {
    }

    default void onToolCallResult(String runId, String sessionId, String toolName, Object result) {
    }
}
