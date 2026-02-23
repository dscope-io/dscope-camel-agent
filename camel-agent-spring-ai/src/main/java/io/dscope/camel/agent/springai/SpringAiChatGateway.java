package io.dscope.camel.agent.springai;

import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.ToolSpec;
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

    record SpringAiChatResult(String message, List<AiToolCall> toolCalls, boolean terminal) {
    }
}
