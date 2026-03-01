package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.ModelOptions;
import io.dscope.camel.agent.model.ModelResponse;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.function.Consumer;

public class SpringAiModelClient implements AiModelClient {

    private final SpringAiChatGateway chatGateway;
    public SpringAiModelClient(SpringAiChatGateway chatGateway, ObjectMapper objectMapper) {
        this.chatGateway = chatGateway;
    }

    @Override
    public ModelResponse generate(String systemInstruction,
                                  List<AgentEvent> history,
                                  List<ToolSpec> tools,
                                  ModelOptions options,
                                  Consumer<String> streamingTokenCallback) {
        String context = history == null || history.isEmpty()
            ? ""
            : history.stream().map(event -> event.type() + ": " + event.payload()).reduce((a, b) -> a + "\n" + b).orElse("");

        SpringAiChatGateway.SpringAiChatResult result = chatGateway.generate(
            systemInstruction,
            context,
            tools,
            options == null ? null : options.model(),
            options == null ? null : options.temperature(),
            options == null ? null : options.maxTokens(),
            streamingTokenCallback
        );

        return new ModelResponse(result.message(), result.toolCalls(), result.terminal());
    }
}
