package io.dscope.camel.agent.model;

import java.util.List;

public record ModelResponse(
    String assistantMessage,
    List<AiToolCall> toolCalls,
    boolean terminal,
    TokenUsage tokenUsage,
    ModelUsage modelUsage
) {

    public ModelResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (tokenUsage == null && modelUsage != null && modelUsage.hasTokenUsage()) {
            tokenUsage = modelUsage.tokenUsage();
        }
    }

    public ModelResponse(String assistantMessage, List<AiToolCall> toolCalls, boolean terminal) {
        this(assistantMessage, toolCalls, terminal, null, null);
    }

    public ModelResponse(String assistantMessage, List<AiToolCall> toolCalls, boolean terminal, TokenUsage tokenUsage) {
        this(assistantMessage, toolCalls, terminal, tokenUsage, null);
    }
}
