package io.dscope.camel.agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.component.AgentComponent;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.ModelOptions;
import io.dscope.camel.agent.model.ModelResponse;
import io.dscope.camel.agent.model.ToolSpec;
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
            Assertions.assertEquals("application/json", exchange.getMessage().getHeader("Content-Type"));
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
        SimpleRegistry registry = new SimpleRegistry();
        registry.bind(ObjectMapper.class.getName(), new ObjectMapper());
        registry.bind(AiModelClient.class.getName(), new EchoAiModelClient());
        registry.bind(InMemoryPersistenceFacade.class.getName(), new InMemoryPersistenceFacade());
        DefaultCamelContext context = new DefaultCamelContext(registry);
        context.addComponent("agent", new AgentComponent());
        context.start();
        return context;
    }

    private static final class EchoAiModelClient implements AiModelClient {

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
            return new ModelResponse(message, List.of(), true);
        }
    }
}