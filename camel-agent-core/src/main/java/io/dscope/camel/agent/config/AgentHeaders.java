package io.dscope.camel.agent.config;

public final class AgentHeaders {

    public static final String CONVERSATION_ID = "agent.conversationId";
    public static final String TASK_ID = "agent.taskId";
    public static final String TOOL_NAME = "agent.toolName";
    public static final String TRACE_ID = "agent.traceId";
    public static final String AGUI_SESSION_ID = "agent.agui.sessionId";
    public static final String AGUI_RUN_ID = "agent.agui.runId";
    public static final String AGUI_THREAD_ID = "agent.agui.threadId";
    public static final String PLAN_NAME = "agent.planName";
    public static final String PLAN_VERSION = "agent.planVersion";
    public static final String RESOLVED_PLAN_NAME = "agent.resolvedPlanName";
    public static final String RESOLVED_PLAN_VERSION = "agent.resolvedPlanVersion";
    public static final String RESOLVED_BLUEPRINT = "agent.resolvedBlueprint";
    public static final String RESPONSE_TURN_EVENTS_PERSISTED = "agent.response.turnEventsPersisted";

    private AgentHeaders() {
    }
}
