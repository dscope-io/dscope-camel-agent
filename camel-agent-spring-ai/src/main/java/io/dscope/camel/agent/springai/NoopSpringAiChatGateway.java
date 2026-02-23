package io.dscope.camel.agent.springai;

import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.function.Consumer;

public class NoopSpringAiChatGateway implements SpringAiChatGateway {

    @Override
    public SpringAiChatResult generate(String systemPrompt,
                                       String userContext,
                                       List<ToolSpec> tools,
                                       String model,
                                       Double temperature,
                                       Integer maxTokens,
                                       Consumer<String> streamingTokenCallback) {
        String message = "No Spring AI provider configured.";
        if (streamingTokenCallback != null) {
            streamingTokenCallback.accept(message);
        }
        return new SpringAiChatResult(message, List.of(), true);
    }
}
