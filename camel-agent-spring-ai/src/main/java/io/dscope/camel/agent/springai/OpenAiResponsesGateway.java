package io.dscope.camel.agent.springai;

import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.function.Consumer;

public interface OpenAiResponsesGateway {

    SpringAiChatGateway.SpringAiChatResult generate(String apiMode,
                                                    String systemPrompt,
                                                    String userContext,
                                                    List<ToolSpec> tools,
                                                    String model,
                                                    Double temperature,
                                                    Integer maxTokens,
                                                    String apiKey,
                                                    String baseUrl,
                                                    Consumer<String> streamingTokenCallback);
}
