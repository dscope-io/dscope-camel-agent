package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.ModelOptions;
import io.dscope.camel.agent.model.ModelUsage;
import io.dscope.camel.agent.model.TokenUsage;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class MultiProviderSpringAiChatGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldSanitizeOpenAiToolNamesAndRemapToolCallsToOriginalNames() throws Exception {
        MultiProviderSpringAiChatGateway gateway = new MultiProviderSpringAiChatGateway(new Properties());
        List<ToolSpec> tools = List.of(
            new ToolSpec("support.ticket.open", "", "support-ticket-open", null, null, null, new ToolPolicy(false, 0, 1000)),
            new ToolSpec("support ticket open", "", "support-ticket-open-2", null, null, null, new ToolPolicy(false, 0, 1000))
        );

        Method toolCallbacksForOpenAi = MultiProviderSpringAiChatGateway.class
            .getDeclaredMethod("toolCallbacksForOpenAi", List.class);
        toolCallbacksForOpenAi.setAccessible(true);

        Object openAiBundle = toolCallbacksForOpenAi.invoke(gateway, tools);

        Method aliasesMethod = openAiBundle.getClass().getDeclaredMethod("aliases");
        aliasesMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> aliases = (Map<String, String>) aliasesMethod.invoke(openAiBundle);

        Assertions.assertEquals(2, aliases.size());
        aliases.keySet().forEach(name -> Assertions.assertTrue(name.matches("^[a-zA-Z0-9_-]+$")));
        Assertions.assertTrue(aliases.containsValue("support.ticket.open"));
        Assertions.assertTrue(aliases.containsValue("support ticket open"));

        List<String> sanitizedNames = aliases.keySet().stream().toList();
        SpringAiChatGateway.SpringAiChatResult modelResult = new SpringAiChatGateway.SpringAiChatResult(
            "ok",
            List.of(
                new AiToolCall(sanitizedNames.get(0), MAPPER.createObjectNode()),
                new AiToolCall(sanitizedNames.get(1), MAPPER.createObjectNode())
            ),
            false
        );

        Method remapToolCallNames = MultiProviderSpringAiChatGateway.class
            .getDeclaredMethod("remapToolCallNames", SpringAiChatGateway.SpringAiChatResult.class, Map.class);
        remapToolCallNames.setAccessible(true);

        SpringAiChatGateway.SpringAiChatResult remapped = (SpringAiChatGateway.SpringAiChatResult) remapToolCallNames
            .invoke(gateway, modelResult, aliases);

        List<String> remappedNames = remapped.toolCalls().stream().map(AiToolCall::name).toList();
        Assertions.assertTrue(remappedNames.contains("support.ticket.open"));
        Assertions.assertTrue(remappedNames.contains("support ticket open"));
    }

    @Test
    void shouldDelegateToResponsesWsGatewayWhenConfigured() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.openai.api-mode", "responses-ws");
        properties.setProperty("agent.runtime.spring-ai.openai.api-key", "test-key");

        AtomicBoolean called = new AtomicBoolean(false);
        OpenAiResponsesGateway responsesGateway = (apiMode,
                                                   systemPrompt,
                                                   userContext,
                                                   tools,
                                                   model,
                                                   temperature,
                                                   maxTokens,
                                                   apiKey,
                                                   baseUrl,
                                                   callback) -> {
            called.set(true);
            Assertions.assertEquals("responses-ws", apiMode);
            Assertions.assertEquals("test-key", apiKey);
            return new SpringAiChatGateway.SpringAiChatResult("ws-ok", List.of(), true);
        };

        MultiProviderSpringAiChatGateway gateway = new MultiProviderSpringAiChatGateway(properties, responsesGateway);
        SpringAiChatGateway.SpringAiChatResult result = gateway.generate(
            "system",
            "user",
            List.of(),
            null,
            null,
            null,
            null
        );

        Assertions.assertTrue(called.get());
        Assertions.assertEquals("ws-ok", result.message());
        Assertions.assertTrue(result.terminal());
    }

    @Test
    void shouldDefaultResponsesWsModelToUpdatedOpenAiBaseline() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.openai.api-mode", "responses-ws");
        properties.setProperty("agent.runtime.spring-ai.openai.api-key", "test-key");

        OpenAiResponsesGateway responsesGateway = (apiMode,
                                                   systemPrompt,
                                                   userContext,
                                                   tools,
                                                   model,
                                                   temperature,
                                                   maxTokens,
                                                   apiKey,
                                                   baseUrl,
                                                   callback) -> {
            Assertions.assertEquals("gpt-5.4", model);
            return new SpringAiChatGateway.SpringAiChatResult("ok", List.of(), true);
        };

        MultiProviderSpringAiChatGateway gateway = new MultiProviderSpringAiChatGateway(properties, responsesGateway);
        SpringAiChatGateway.SpringAiChatResult result = gateway.generate(
            "system",
            "user",
            List.of(),
            null,
            null,
            null,
            null
        );

        Assertions.assertEquals("ok", result.message());
        Assertions.assertTrue(result.terminal());
    }

    @Test
    void shouldReturnHelpfulMessageForResponsesHttpMode() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.openai.api-mode", "responses-http");
        properties.setProperty("agent.runtime.spring-ai.openai.api-key", "test-key");

        AtomicBoolean called = new AtomicBoolean(false);
        OpenAiResponsesGateway responsesGateway = (apiMode,
                                                   systemPrompt,
                                                   userContext,
                                                   tools,
                                                   model,
                                                   temperature,
                                                   maxTokens,
                                                   apiKey,
                                                   baseUrl,
                                                   callback) -> {
            called.set(true);
            Assertions.assertEquals("responses-http", apiMode);
            return new SpringAiChatGateway.SpringAiChatResult("http-ok", List.of(), true);
        };

        MultiProviderSpringAiChatGateway gateway = new MultiProviderSpringAiChatGateway(properties, responsesGateway);
        SpringAiChatGateway.SpringAiChatResult result = gateway.generate(
            "system",
            "user",
            List.of(),
            null,
            null,
            null,
            null
        );

        Assertions.assertTrue(called.get());
        Assertions.assertTrue(result.terminal());
        Assertions.assertEquals("http-ok", result.message());
    }

    @Test
    void shouldApplyPerCallOverridesFromModelOptions() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.provider", "gemini");

        AtomicBoolean called = new AtomicBoolean(false);
        OpenAiResponsesGateway responsesGateway = (apiMode,
                                                   systemPrompt,
                                                   userContext,
                                                   tools,
                                                   model,
                                                   temperature,
                                                   maxTokens,
                                                   apiKey,
                                                   baseUrl,
                                                   callback) -> {
            called.set(true);
            Assertions.assertEquals("responses-http", apiMode);
            Assertions.assertEquals("gpt-5.4-mini", model);
            Assertions.assertEquals("plan-key", apiKey);
            return new SpringAiChatGateway.SpringAiChatResult("plan-ok", List.of(), true);
        };

        MultiProviderSpringAiChatGateway gateway = new MultiProviderSpringAiChatGateway(properties, responsesGateway);
        SpringAiChatGateway.SpringAiChatResult result = gateway.generate(
            "system",
            "user",
            List.of(),
            new ModelOptions(
                "openai",
                "gpt-5.4-mini",
                0.3d,
                256,
                true,
                Map.of(
                    "agent.runtime.spring-ai.openai.api-mode", "responses-http",
                    "agent.runtime.spring-ai.openai.api-key", "plan-key"
                )
            ),
            null
        );

        Assertions.assertTrue(called.get());
        Assertions.assertEquals("plan-ok", result.message());
    }

    @Test
    void shouldPreferConfiguredApiKeySystemPropertyOverOpenAiApiKeyReference() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.openai.api-mode", "responses-ws");
        properties.setProperty("agent.runtime.spring-ai.openai.api-key-system-property", "openai.api.key");
        properties.setProperty("spring.ai.openai.api-key", "${OPENAI_API_KEY}");

        String previousOpenAiApiKeyReference = System.getProperty("OPENAI_API_KEY");
        String previousOpenAiApiKey = System.getProperty("openai.api.key");
        System.setProperty("OPENAI_API_KEY", "placeholder-priority-key");
        System.setProperty("openai.api.key", "system-priority-key");
        try {
            OpenAiResponsesGateway responsesGateway = (apiMode,
                                                       systemPrompt,
                                                       userContext,
                                                       tools,
                                                       model,
                                                       temperature,
                                                       maxTokens,
                                                       apiKey,
                                                       baseUrl,
                                                       callback) -> {
                Assertions.assertEquals("system-priority-key", apiKey);
                return new SpringAiChatGateway.SpringAiChatResult("ok", List.of(), true);
            };

            MultiProviderSpringAiChatGateway gateway = new MultiProviderSpringAiChatGateway(properties, responsesGateway);
            SpringAiChatGateway.SpringAiChatResult result = gateway.generate(
                "system",
                "user",
                List.of(),
                null,
                null,
                null,
                null
            );

            Assertions.assertEquals("ok", result.message());
        } finally {
            if (previousOpenAiApiKeyReference == null) {
                System.clearProperty("OPENAI_API_KEY");
            } else {
                System.setProperty("OPENAI_API_KEY", previousOpenAiApiKeyReference);
            }
            if (previousOpenAiApiKey == null) {
                System.clearProperty("openai.api.key");
            } else {
                System.setProperty("openai.api.key", previousOpenAiApiKey);
            }
        }
    }

    @Test
    void shouldExtractTokenUsageFromSpringAiChatResponseMetadata() throws Exception {
        MultiProviderSpringAiChatGateway gateway = new MultiProviderSpringAiChatGateway(new Properties());
        ChatResponse response = new ChatResponse(
            List.of(new Generation(new AssistantMessage("ok"))),
            ChatResponseMetadata.builder().usage(new DefaultUsage(9, 4, 13)).build()
        );

        Method extractTokenUsage = MultiProviderSpringAiChatGateway.class
            .getDeclaredMethod("extractTokenUsage", ChatResponse.class);
        extractTokenUsage.setAccessible(true);

        TokenUsage tokenUsage = (TokenUsage) extractTokenUsage.invoke(gateway, response);

        Assertions.assertNotNull(tokenUsage);
        Assertions.assertTrue(java.util.Objects.equals(9, tokenUsage.promptTokens()));
        Assertions.assertTrue(java.util.Objects.equals(4, tokenUsage.completionTokens()));
        Assertions.assertTrue(java.util.Objects.equals(13, tokenUsage.totalTokens()));
    }

    @Test
    void shouldEstimateOpenAiModelUsageCostWhenConfigured() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.model-cost.openai.gpt-5-4.prompt-usd-per-1m", "1.00");
        properties.setProperty("agent.runtime.model-cost.openai.gpt-5-4.completion-usd-per-1m", "2.00");

        ModelCostEstimator estimator = new ModelCostEstimator(properties);
        ModelUsage usage = estimator.estimate("openai", "gpt-5.4", "chat", TokenUsage.of(10, 5, 15));

        Assertions.assertNotNull(usage);
        Assertions.assertEquals("openai", usage.provider());
        Assertions.assertEquals("gpt-5.4", usage.model());
        Assertions.assertEquals(new java.math.BigDecimal("0.00002000"), usage.totalCostUsd());
    }

    @Test
    void shouldUseMaxCompletionTokensForGpt5Models() throws Exception {
        MultiProviderSpringAiChatGateway gateway = new MultiProviderSpringAiChatGateway(new Properties());
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("gpt-5.4").build();

        Method applyOpenAiTokenLimit = MultiProviderSpringAiChatGateway.class
            .getDeclaredMethod("applyOpenAiTokenLimit", OpenAiChatOptions.class, String.class, Integer.class);
        applyOpenAiTokenLimit.setAccessible(true);
        applyOpenAiTokenLimit.invoke(gateway, options, "gpt-5.4", 1024);

        Assertions.assertNull(options.getMaxTokens());
        Assertions.assertEquals(1024, options.getMaxCompletionTokens());
    }

    @Test
    void shouldUseLegacyMaxTokensForNonReasoningOpenAiModels() throws Exception {
        MultiProviderSpringAiChatGateway gateway = new MultiProviderSpringAiChatGateway(new Properties());
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("gpt-4o").build();

        Method applyOpenAiTokenLimit = MultiProviderSpringAiChatGateway.class
            .getDeclaredMethod("applyOpenAiTokenLimit", OpenAiChatOptions.class, String.class, Integer.class);
        applyOpenAiTokenLimit.setAccessible(true);
        applyOpenAiTokenLimit.invoke(gateway, options, "gpt-4o", 512);

        Assertions.assertEquals(512, options.getMaxTokens());
        Assertions.assertNull(options.getMaxCompletionTokens());
    }
}
