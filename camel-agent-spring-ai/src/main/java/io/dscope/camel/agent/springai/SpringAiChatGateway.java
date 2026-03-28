package io.dscope.camel.agent.springai;

import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.ModelUsage;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.model.TokenUsage;
import java.util.List;
import java.util.function.Consumer;

public interface SpringAiChatGateway {

    SpringAiChatResult generate(String systemPrompt,
                                String userContext,
                                List<ToolSpec> tools,
                                String model,
                                Double temperature,
                                Integer maxTokens,
                                Consumer<String> streamingTokenCallback);

    record SpringAiChatResult(String message,
                              List<AiToolCall> toolCalls,
                              boolean terminal,
                              TokenUsage tokenUsage,
                              ModelUsage modelUsage) {

        public SpringAiChatResult {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (tokenUsage == null && modelUsage != null && modelUsage.hasTokenUsage()) {
                tokenUsage = modelUsage.tokenUsage();
            }
        }

        public SpringAiChatResult(String message, List<AiToolCall> toolCalls, boolean terminal) {
            this(message, toolCalls, terminal, null, null);
        }

        public SpringAiChatResult(String message, List<AiToolCall> toolCalls, boolean terminal, TokenUsage tokenUsage) {
            this(message, toolCalls, terminal, tokenUsage, null);
        }
    }
}
