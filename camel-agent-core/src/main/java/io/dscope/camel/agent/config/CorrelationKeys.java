package io.dscope.camel.agent.config;

public final class CorrelationKeys {

    public static final String AGUI_SESSION_ID = "agui.sessionId";
    public static final String AGUI_RUN_ID = "agui.runId";
    public static final String AGUI_THREAD_ID = "agui.threadId";
    public static final String A2A_AGENT_ID = "a2a.agentId";
    public static final String A2A_REMOTE_CONVERSATION_ID = "a2a.remoteConversationId";
    public static final String A2A_REMOTE_TASK_ID = "a2a.remoteTaskId";
    public static final String A2A_LINKED_CONVERSATION_ID = "a2a.linkedConversationId";
    public static final String A2A_PARENT_CONVERSATION_ID = "a2a.parentConversationId";
    public static final String A2A_ROOT_CONVERSATION_ID = "a2a.rootConversationId";

    private CorrelationKeys() {
    }
}
