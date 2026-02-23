package io.dscope.camel.agent.api;

import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.ModelOptions;
import io.dscope.camel.agent.model.ModelResponse;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.function.Consumer;

public interface AiModelClient {

    ModelResponse generate(String systemInstruction,
                           List<AgentEvent> history,
                           List<ToolSpec> tools,
                           ModelOptions options,
                           Consumer<String> streamingTokenCallback);
}
