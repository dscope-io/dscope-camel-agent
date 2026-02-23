package io.dscope.camel.agent.model;

import java.time.Instant;

public record TaskState(
    String taskId,
    String conversationId,
    TaskStatus status,
    String checkpoint,
    Instant nextWakeup,
    int retries,
    String result
) {
}
