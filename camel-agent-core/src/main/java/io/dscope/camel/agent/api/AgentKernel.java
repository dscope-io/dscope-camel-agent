package io.dscope.camel.agent.api;

import io.dscope.camel.agent.model.AgentResponse;

public interface AgentKernel {

    AgentResponse handleUserMessage(String conversationId, String userMessage);

    AgentResponse resumeTask(String taskId);
}
