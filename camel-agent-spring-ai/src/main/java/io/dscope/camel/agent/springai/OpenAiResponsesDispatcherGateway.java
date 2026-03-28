package io.dscope.camel.agent.springai;

import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

final class OpenAiResponsesDispatcherGateway implements OpenAiResponsesGateway {

    private final OpenAiResponsesGateway sdkGateway;
    private final OpenAiResponsesGateway realtimeGateway;

    OpenAiResponsesDispatcherGateway(Properties properties) {
        this(new OpenAiSdkResponsesGateway(properties), new OpenAiRealtimeResponsesGateway(properties));
    }

    OpenAiResponsesDispatcherGateway(OpenAiResponsesGateway sdkGateway, OpenAiResponsesGateway realtimeGateway) {
        this.sdkGateway = sdkGateway;
        this.realtimeGateway = realtimeGateway;
    }

    @Override
    public SpringAiChatGateway.SpringAiChatResult generate(String apiMode,
                                                           String systemPrompt,
                                                           String userContext,
                                                           List<ToolSpec> tools,
                                                           String model,
                                                           Double temperature,
                                                           Integer maxTokens,
                                                           String apiKey,
                                                           String baseUrl,
                                                           Consumer<String> streamingTokenCallback) {
        if (isResponsesWs(apiMode)) {
            return realtimeGateway.generate(apiMode, systemPrompt, userContext, tools, model, temperature, maxTokens, apiKey, baseUrl, streamingTokenCallback);
        }
        return sdkGateway.generate(apiMode, systemPrompt, userContext, tools, model, temperature, maxTokens, apiKey, baseUrl, streamingTokenCallback);
    }

    private boolean isResponsesWs(String apiMode) {
        if (apiMode == null) {
            return false;
        }
        String normalized = apiMode.trim().toLowerCase();
        return "responses-ws".equals(normalized) || "responses_ws".equals(normalized) || "responses.ws".equals(normalized);
    }
}