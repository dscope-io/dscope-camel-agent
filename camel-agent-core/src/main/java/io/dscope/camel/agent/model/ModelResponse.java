package io.dscope.camel.agent.model;

import java.util.List;

public record ModelResponse(
    String assistantMessage,
    List<AiToolCall> toolCalls,
    boolean terminal
) {
}
