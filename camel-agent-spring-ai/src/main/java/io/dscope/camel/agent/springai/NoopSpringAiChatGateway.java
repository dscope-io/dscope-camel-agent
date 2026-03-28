package io.dscope.camel.agent.springai;

import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.model.ModelOptions;
import java.util.List;
import java.util.function.Consumer;

public class NoopSpringAiChatGateway implements ConfigurableSpringAiChatGateway {

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

    @Override
    public SpringAiChatResult generate(String systemPrompt,
                                       String userContext,
                                       List<ToolSpec> tools,
                                       ModelOptions options,
                                       Consumer<String> streamingTokenCallback) {
        return generate(
            systemPrompt,
            userContext,
            tools,
            options == null ? null : options.model(),
            options == null ? null : options.temperature(),
            options == null ? null : options.maxTokens(),
            streamingTokenCallback
        );
    }
}
