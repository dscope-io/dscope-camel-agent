package io.dscope.camel.agent.springai;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenAiSdkResponsesGatewayTest {

    @Test
    void shouldAddPromptCacheFieldsAndMapResponseUsage() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.openai.prompt-cache.enabled", "true");
        properties.setProperty("agent.runtime.spring-ai.openai.prompt-cache.key", "support-cache-v1");
        properties.setProperty("agent.runtime.spring-ai.openai.prompt-cache.retention", "24h");
        properties.setProperty("agent.runtime.model-cost.openai.gpt-5-4.prompt-usd-per-1m", "1.00");
        properties.setProperty("agent.runtime.model-cost.openai.gpt-5-4.completion-usd-per-1m", "2.00");

        AtomicReference<ResponseCreateParams> captured = new AtomicReference<>();
        OpenAiSdkResponsesGateway gateway = new OpenAiSdkResponsesGateway(properties, (request, apiKey, baseUrl, organization, project) -> {
            captured.set(request);
            return responseFixture();
        });

        SpringAiChatGateway.SpringAiChatResult result = gateway.generate(
            "responses-http",
            "system rules",
            "user question",
            java.util.List.of(),
            "gpt-5.4",
            0.2,
            250,
            "test-key",
            "https://api.openai.com",
            null
        );

        ResponseCreateParams request = captured.get();
        Assertions.assertNotNull(request);
        Assertions.assertEquals("support-cache-v1", request._additionalBodyProperties().get("prompt_cache_key").convert(String.class));
        Assertions.assertEquals("24h", request._additionalBodyProperties().get("prompt_cache_retention").convert(String.class));
        Assertions.assertTrue(result.message().contains("OpenAI responses HTTP invocation error"));
    }

    private Response responseFixture() {
        return Response.builder().build();
    }
}