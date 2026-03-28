package io.dscope.camel.agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.component.AgentComponent;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.ModelUsage;
import io.dscope.camel.agent.model.ModelOptions;
import io.dscope.camel.agent.model.ModelResponse;
import io.dscope.camel.agent.model.TokenUsage;
import io.dscope.camel.agent.model.ToolSpec;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.SimpleRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentSessionServiceTest {

    @Test
    void shouldCreateStructuredSessionResponseForNewSession() throws Exception {
        try (CamelContext context = createContext()) {
            AgentSessionService service = new AgentSessionService();
            var exchange = new DefaultExchange(context);

            AgentSessionResponse response = service.invoke(
                exchange,
                new AgentSessionRequest(
                    "hello from route",
                    "",
                    "",
                    "",
                    "",
                    "",
                    Map.of("customerId", "123")
                ),
                "agent:support?blueprint=classpath:agents/valid-agent.md"
            );

            Assertions.assertFalse(response.conversationId().isBlank());
            Assertions.assertEquals(response.conversationId(), response.sessionId());
            Assertions.assertTrue(response.created());
            Assertions.assertTrue(response.message().startsWith("reply:"));
            Assertions.assertEquals("123", response.params().get("customerId"));
            Assertions.assertEquals(1, ((Number) ((Map<?, ?>) response.modelUsage().get("turn")).get("callCount")).intValue());
            Assertions.assertEquals(response.conversationId(), exchange.getMessage().getHeader(AgentHeaders.CONVERSATION_ID));
            Assertions.assertEquals(Map.of("customerId", "123"), exchange.getProperty(AgentSessionService.SESSION_PARAMS_PROPERTY));
        }
    }

    @Test
    void shouldReuseProvidedSessionAliasAndPreserveTurnMetadata() throws Exception {
        try (CamelContext context = createContext()) {
            AgentSessionService service = new AgentSessionService();
            var exchange = new DefaultExchange(context);

            AgentSessionResponse response = service.invoke(
                exchange,
                new AgentSessionRequest(
                    "continue please",
                    "",
                    "session-42",
                    "",
                    "",
                    "",
                    Map.of("channel", "route")
                ),
                "agent:support?blueprint=classpath:agents/valid-agent.md"
            );

            Assertions.assertEquals("session-42", response.conversationId());
            Assertions.assertEquals("session-42", response.sessionId());
            Assertions.assertFalse(response.created());
            Assertions.assertNotNull(response.taskState());
            Assertions.assertFalse(response.events().isEmpty());
            Assertions.assertTrue(response.events().stream().anyMatch(event -> "user.message".equals(event.type())));
            Map<?, ?> turnUsage = (Map<?, ?>) response.modelUsage().get("turn");
            Map<?, ?> sessionUsage = (Map<?, ?>) response.modelUsage().get("session");
            Assertions.assertEquals(1, ((Number) turnUsage.get("callCount")).intValue());
            Assertions.assertEquals(12, ((Number) ((Map<?, ?>) turnUsage.get("totals")).get("totalTokens")).intValue());
            Assertions.assertEquals(12, ((Number) ((Map<?, ?>) sessionUsage.get("totals")).get("totalTokens")).intValue());
        }
    }

    @Test
    void shouldParseJsonBodyThroughProcessor() throws Exception {
        try (CamelContext context = createContext()) {
            AgentSessionInvokeProcessor processor = new AgentSessionInvokeProcessor(new AgentSessionService(), new ObjectMapper());
            var exchange = new DefaultExchange(context);
            exchange.getMessage().setBody("""
                {
                  "prompt": "json invoke",
                  "sessionId": "json-session-1",
                  "params": {
                    "locale": "en-US"
                  }
                }
                """);
            exchange.getMessage().setHeader("agent.session.endpointUri", "agent:support?blueprint=classpath:agents/valid-agent.md");

            processor.process(exchange);

            String json = exchange.getMessage().getBody(String.class);
            Assertions.assertTrue(json.contains("\"conversationId\":\"json-session-1\""));
            Assertions.assertTrue(json.contains("\"sessionId\":\"json-session-1\""));
            Assertions.assertTrue(json.contains("\"locale\":\"en-US\""));
            Assertions.assertTrue(json.contains("\"modelUsage\""));
            Assertions.assertTrue(json.contains("\"totalTokens\":12"));
            Assertions.assertEquals("application/json", exchange.getMessage().getHeader("Content-Type"));
        }
    }

    @Test
    void shouldExposeResolvedAiOverridesForSelectedPlanVersion() throws Exception {
        CapturingAiModelClient client = new CapturingAiModelClient();
        try (CamelContext context = createContext(client)) {
            AgentSessionService service = new AgentSessionService();
            var exchange = new DefaultExchange(context);

            AgentSessionResponse response = service.invoke(
                exchange,
                new AgentSessionRequest(
                    "route with plan overrides",
                    "",
                    "session-plan-ai",
                    "",
                    "support",
                    "v2",
                    Map.of()
                ),
                "agent:support?plansConfig=classpath:runtime/test-agents.yaml&blueprint=classpath:agents/valid-agent.md"
            );

            Assertions.assertEquals("openai", response.resolvedAi().get("provider"));
            Assertions.assertEquals("gpt-5.4-mini", response.resolvedAi().get("model"));
            Assertions.assertEquals(512, ((Number) response.resolvedAi().get("maxTokens")).intValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) response.resolvedAi().get("properties");
            Assertions.assertEquals("responses-http", properties.get("agent.runtime.spring-ai.openai.api-mode"));
            Assertions.assertEquals("true", properties.get("agent.runtime.spring-ai.openai.prompt-cache.enabled"));
            Assertions.assertNotNull(client.lastOptions);
            Assertions.assertEquals("openai", client.lastOptions.provider());
            Assertions.assertEquals("gpt-5.4-mini", client.lastOptions.model());
            Assertions.assertEquals(512, client.lastOptions.maxTokens());
            Assertions.assertEquals("true", client.lastOptions.properties().get("agent.runtime.spring-ai.openai.prompt-cache.enabled"));
        }
    }

    @Test
    void shouldFailFastWhenSessionEndpointPlaceholderIsUnresolved() throws Exception {
        try (CamelContext context = createContext()) {
            AgentSessionService service = new AgentSessionService();
            var exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("agent.session.endpointUri", "agent:${UNKNOWN_AGENT_ENDPOINT}");

            IllegalArgumentException error = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveAgentEndpointUri(exchange)
            );

            Assertions.assertEquals(
                "Unresolved runtime placeholder in agent.session.endpointUri: agent:${UNKNOWN_AGENT_ENDPOINT}",
                error.getMessage()
            );
        }
    }

    private CamelContext createContext() throws Exception {
        return createContext(new EchoAiModelClient());
    }

    private CamelContext createContext(AiModelClient client) throws Exception {
        SimpleRegistry registry = new SimpleRegistry();
        registry.bind(ObjectMapper.class.getName(), new ObjectMapper());
        registry.bind(AiModelClient.class.getName(), client);
        registry.bind(InMemoryPersistenceFacade.class.getName(), new InMemoryPersistenceFacade());
        DefaultCamelContext context = new DefaultCamelContext(registry);
        context.addComponent("agent", new AgentComponent());
        context.start();
        return context;
    }

    private static class EchoAiModelClient implements AiModelClient {

        @Override
        public ModelResponse generate(String systemInstruction,
                                      List<AgentEvent> history,
                                      List<ToolSpec> tools,
                                      ModelOptions options,
                                      java.util.function.Consumer<String> streamingTokenCallback) {
            String prompt = history == null
                ? ""
                : history.stream()
                    .filter(event -> "user.message".equals(event.type()))
                    .reduce((first, second) -> second)
                    .map(event -> event.payload().asText(""))
                    .orElse("");
            String message = "reply:" + prompt;
            ModelUsage modelUsage = ModelUsage.of(
                "openai",
                "gpt-5.4",
                "chat",
                TokenUsage.of(8, 4, 12),
                new BigDecimal("0.00008"),
                new BigDecimal("0.00004"),
                new BigDecimal("0.00012")
            );
            return new ModelResponse(message, List.of(), true, null, modelUsage);
        }
    }

    private static final class CapturingAiModelClient extends EchoAiModelClient {

        private ModelOptions lastOptions;

        @Override
        public ModelResponse generate(String systemInstruction,
                                      List<AgentEvent> history,
                                      List<ToolSpec> tools,
                                      ModelOptions options,
                                      java.util.function.Consumer<String> streamingTokenCallback) {
            lastOptions = options;
            return super.generate(systemInstruction, history, tools, options, streamingTokenCallback);
        }
    }
}