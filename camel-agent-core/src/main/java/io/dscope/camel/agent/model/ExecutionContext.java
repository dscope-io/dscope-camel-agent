package io.dscope.camel.agent.model;

import java.util.List;

public record ExecutionContext(
    String conversationId,
    String taskId,
    String traceId,
    List<ExceptionPolicySpec> exceptionPolicies
) {

    public ExecutionContext {
        exceptionPolicies = exceptionPolicies == null ? List.of() : List.copyOf(exceptionPolicies);
    }

    public ExecutionContext(
        String conversationId,
        String taskId,
        String traceId
    ) {
        this(conversationId, taskId, traceId, List.of());
    }
}
