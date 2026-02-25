package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.DefaultAgentKernel;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.AgentResponse;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.registry.DefaultToolRegistry;
import io.dscope.camel.agent.validation.SchemaValidator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ResponsesWsMemoryIntegrationTest {

    @Test
    void shouldCarryFirstTurnContextIntoSecondTurnWhenUsingResponsesWsMode() {
        ObjectMapper mapper = new ObjectMapper();
        String firstPrompt = "Search KB for login troubleshooting guidance";
        String secondPrompt = "Create ticket with that prior KB context";

        ToolSpec kbSearch = new ToolSpec(
            "kb.search",
            "Search KB",
            "kb-search",
            null,
            mapper.createObjectNode().put("type", "object"),
            mapper.createObjectNode().put("type", "object"),
            new ToolPolicy(false, 0, 1_000)
        );
        ToolSpec ticketOpen = new ToolSpec(
            "support.ticket.open",
            "Open ticket",
            "support-ticket-open",
            null,
            mapper.createObjectNode().put("type", "object"),
            mapper.createObjectNode().put("type", "object"),
            new ToolPolicy(false, 0, 1_000)
        );

        AgentBlueprint blueprint = new AgentBlueprint(
            "SupportAssistant",
            "0.1.0",
            "You are a support assistant.",
            List.of(kbSearch, ticketOpen),
            List.of()
        );

        AtomicReference<String> secondTurnContext = new AtomicReference<>("");
        OpenAiResponsesGateway responsesGateway = new OpenAiResponsesGateway() {
            private int callCount;

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
                                                                   java.util.function.Consumer<String> streamingTokenCallback) {
                callCount++;
                if (callCount == 1) {
                    return new SpringAiChatGateway.SpringAiChatResult(
                        "",
                        List.of(new AiToolCall("kb.search", mapper.createObjectNode().put("query", firstPrompt))),
                        true
                    );
                }

                secondTurnContext.set(userContext == null ? "" : userContext);
                Assertions.assertTrue(secondTurnContext.get().contains("Knowledge base result for " + firstPrompt));
                return new SpringAiChatGateway.SpringAiChatResult(
                    "",
                    List.of(new AiToolCall("support.ticket.open", mapper.createObjectNode().put("query", "Escalate with prior context: Knowledge base result for " + firstPrompt))),
                    true
                );
            }
        };

        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.provider", "openai");
        properties.setProperty("agent.runtime.spring-ai.openai.api-mode", "responses-ws");
        properties.setProperty("agent.runtime.spring-ai.openai.api-key", "test-key");

        SpringAiModelClient modelClient = new SpringAiModelClient(
            new MultiProviderSpringAiChatGateway(properties, responsesGateway),
            mapper
        );

        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();
        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            (toolSpec, arguments, context) -> executeTool(toolSpec.name(), arguments, mapper),
            modelClient,
            persistence,
            new SchemaValidator(),
            mapper
        );

        AgentResponse first = kernel.handleUserMessage("conv-responses-ws-memory", firstPrompt);
        AgentResponse second = kernel.handleUserMessage("conv-responses-ws-memory", secondPrompt);

        Assertions.assertEquals("Knowledge base result for " + firstPrompt, first.message());
        Assertions.assertEquals("Support ticket created successfully", second.message());
        Assertions.assertTrue(secondTurnContext.get().contains("tool.result"));
        Assertions.assertTrue(secondTurnContext.get().contains("Knowledge base result for " + firstPrompt));

        List<AgentEvent> conversation = persistence.loadConversation("conv-responses-ws-memory", 100);
        Assertions.assertTrue(conversation.stream().filter(event -> "user.message".equals(event.type())).count() >= 2);
        Assertions.assertTrue(conversation.stream().anyMatch(event -> "tool.result".equals(event.type())
            && event.payload() != null
            && event.payload().toString().contains("Knowledge base result for " + firstPrompt)));
    }

    private ToolResult executeTool(String toolName, JsonNode arguments, ObjectMapper mapper) {
        String query = arguments == null ? "" : arguments.path("query").asText("");
        if ("kb.search".equals(toolName)) {
            String message = "Knowledge base result for " + query;
            return new ToolResult(message, mapper.createObjectNode().put("answer", message), List.of());
        }
        if ("support.ticket.open".equals(toolName)) {
            return new ToolResult(
                "Support ticket created successfully",
                mapper.createObjectNode().put("status", "OPEN").put("summary", query),
                List.of()
            );
        }
        return new ToolResult("", mapper.nullNode(), List.of());
    }
}
