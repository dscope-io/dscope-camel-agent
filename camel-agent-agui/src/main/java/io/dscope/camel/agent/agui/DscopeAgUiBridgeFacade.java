package io.dscope.camel.agent.agui;

import io.dscope.camel.agent.agui.bridge.AgUiTaskEventBridge;
import io.dscope.camel.agent.agui.bridge.AgUiToolEventBridge;
import java.lang.reflect.Method;
import java.util.Map;

public class DscopeAgUiBridgeFacade implements AgUiToolEventBridge, AgUiTaskEventBridge {

    private final Object dscopeToolBridge;
    private final Object dscopeTaskBridge;

    public DscopeAgUiBridgeFacade(Object dscopeToolBridge, Object dscopeTaskBridge) {
        this.dscopeToolBridge = dscopeToolBridge;
        this.dscopeTaskBridge = dscopeTaskBridge;
    }

    @Override
    public void onToolCallStart(String runId, String sessionId, String toolName, Map<String, Object> args) {
        invoke(dscopeToolBridge, "onToolCallStart", runId, sessionId, toolName, args);
    }

    @Override
    public void onToolCallResult(String runId, String sessionId, String toolName, Object result) {
        invoke(dscopeToolBridge, "onToolCallResult", runId, sessionId, toolName, result);
    }

    @Override
    public void onTaskEvent(String runId, String sessionId, String eventType, Map<String, Object> payload) {
        invoke(dscopeTaskBridge, "onTaskEvent", runId, sessionId, eventType, payload);
    }

    private void invoke(Object target, String methodName, Object... args) {
        if (target == null) {
            return;
        }
        try {
            Method method = null;
            for (Method candidate : target.getClass().getMethods()) {
                if (candidate.getName().equals(methodName) && candidate.getParameterCount() == args.length) {
                    method = candidate;
                    break;
                }
            }
            if (method != null) {
                method.invoke(target, args);
            }
        } catch (Exception ignored) {
            // Keep phase-1 adapter non-fatal when bridge implementation is missing or incompatible.
        }
    }
}
