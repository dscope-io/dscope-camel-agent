package io.dscope.camel.agent.config;

public final class AgentHeaders {

    public static final String CONVERSATION_ID = "agent.conversationId";
    public static final String TASK_ID = "agent.taskId";
    public static final String TOOL_NAME = "agent.toolName";
    public static final String TRACE_ID = "agent.traceId";
    public static final String AGUI_SESSION_ID = "agent.agui.sessionId";
    public static final String AGUI_RUN_ID = "agent.agui.runId";
    public static final String AGUI_THREAD_ID = "agent.agui.threadId";
    public static final String A2A_AGENT_ID = "agent.a2a.agentId";
    public static final String A2A_REMOTE_CONVERSATION_ID = "agent.a2a.remoteConversationId";
    public static final String A2A_REMOTE_TASK_ID = "agent.a2a.remoteTaskId";
    public static final String A2A_LINKED_CONVERSATION_ID = "agent.a2a.linkedConversationId";
    public static final String A2A_PARENT_CONVERSATION_ID = "agent.a2a.parentConversationId";
    public static final String A2A_ROOT_CONVERSATION_ID = "agent.a2a.rootConversationId";
    public static final String PLAN_NAME = "agent.planName";
    public static final String PLAN_VERSION = "agent.planVersion";
    public static final String RESOLVED_PLAN_NAME = "agent.resolvedPlanName";
    public static final String RESOLVED_PLAN_VERSION = "agent.resolvedPlanVersion";
    public static final String RESOLVED_BLUEPRINT = "agent.resolvedBlueprint";
    public static final String RESPONSE_TURN_EVENTS_PERSISTED = "agent.response.turnEventsPersisted";

    private AgentHeaders() {
    }
}
