package io.dscope.camel.agent.kernel;

import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.ModelOptions;
import io.dscope.camel.agent.model.ModelResponse;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.function.Consumer;

public class StaticAiModelClient implements AiModelClient {

    @Override
    public ModelResponse generate(String systemInstruction,
                                  List<AgentEvent> history,
                                  List<ToolSpec> tools,
                                  ModelOptions options,
                                  Consumer<String> streamingTokenCallback) {
        String text = "Agent response generated without configured external model client.";
        if (options != null && options.streaming() && streamingTokenCallback != null) {
            for (String token : text.split(" ")) {
                streamingTokenCallback.accept(token + " ");
            }
        }
        return new ModelResponse(text, List.of(), true);
    }
}
