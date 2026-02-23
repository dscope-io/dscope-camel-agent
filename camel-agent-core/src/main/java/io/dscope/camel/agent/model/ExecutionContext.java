package io.dscope.camel.agent.model;

public record ExecutionContext(
    String conversationId,
    String taskId,
    String traceId
) {
}
