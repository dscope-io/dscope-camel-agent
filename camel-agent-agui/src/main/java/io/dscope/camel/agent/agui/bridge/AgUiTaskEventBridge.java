package io.dscope.camel.agent.agui.bridge;

import java.util.Map;

public interface AgUiTaskEventBridge {

    default void onTaskEvent(String runId, String sessionId, String eventType, Map<String, Object> payload) {
    }
}
