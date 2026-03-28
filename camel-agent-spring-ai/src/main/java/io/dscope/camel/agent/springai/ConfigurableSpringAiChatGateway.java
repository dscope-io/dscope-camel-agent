package io.dscope.camel.agent.springai;

import io.dscope.camel.agent.model.ModelOptions;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.function.Consumer;

public interface ConfigurableSpringAiChatGateway extends SpringAiChatGateway {

    SpringAiChatResult generate(String systemPrompt,
                                String userContext,
                                List<ToolSpec> tools,
                                ModelOptions options,
                                Consumer<String> streamingTokenCallback);
}