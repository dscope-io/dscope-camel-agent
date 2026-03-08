package io.dscope.camel.agent.samples;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.kernel.DefaultAgentKernel;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.AgentResponse;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.persistence.dscope.DscopePersistenceFactory;
import io.dscope.camel.agent.registry.DefaultToolRegistry;
import io.dscope.camel.agent.springai.DscopeChatMemoryRepositoryFactory;
import io.dscope.camel.agent.springai.MultiProviderSpringAiChatGateway;
import io.dscope.camel.agent.springai.SpringAiChatGateway;
import io.dscope.camel.agent.springai.SpringAiModelClient;
import io.dscope.camel.agent.validation.SchemaValidator;

class SpringAiAuditTrailIntegrationTest {

    @Test
    @Timeout(90)
    void shouldUseLlmDecisionAndCarryFirstTurnResultIntoSecondTurnContext() {
        boolean liveEnabled = Boolean.parseBoolean(System.getProperty("it.live.openai.enabled", "false"))
            || Boolean.parseBoolean(System.getenv().getOrDefault("IT_LIVE_OPENAI_ENABLED", "false"));
        Assumptions.assumeTrue(
            liveEnabled,
            "Live OpenAI test disabled. Set -Dit.live.openai.enabled=true or IT_LIVE_OPENAI_ENABLED=true to run"
        );

        String apiKey = resolveOpenAiApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY (or openai.api.key/.agent-secrets.properties) is required");

        String conversationId = "conv-" + UUID.randomUUID();
        ObjectMapper objectMapper = new ObjectMapper();
        Properties properties = testProperties();
        properties.setProperty("agent.runtime.spring-ai.provider", "openai");
        properties.setProperty("agent.runtime.spring-ai.openai.api-mode", "chat");
        properties.setProperty("agent.runtime.spring-ai.openai.model", "gpt-4o-mini");
        properties.setProperty("agent.runtime.spring-ai.openai.api-key", apiKey);

        PersistenceFacade persistenceFacade = DscopePersistenceFactory.create(properties, objectMapper);
        CapturingGateway gateway = new CapturingGateway(new MultiProviderSpringAiChatGateway(properties));
        SpringAiModelClient modelClient = new SpringAiModelClient(gateway, objectMapper);
        var blueprint = new MarkdownBlueprintLoader().load("classpath:agents/support/agent.md");

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            (toolSpec, arguments, context) -> executeTool(toolSpec.name(), arguments, objectMapper),
            modelClient,
            persistenceFacade,
            new SchemaValidator(),
            objectMapper
        );

        AgentResponse first = kernel.handleUserMessage(conversationId,
            "Search the knowledge base for login troubleshooting steps. Do not create a ticket yet.");
        AgentResponse second = kernel.handleUserMessage(conversationId,
            "Now please file a support ticket using that knowledge base result.");

        Assertions.assertEquals("kb.search", toolName(first.events()));
        Assertions.assertEquals("support.ticket.open", toolName(second.events()));
        Assertions.assertNotNull(gateway.secondTurnContext);
        Assertions.assertTrue(gateway.secondTurnContext.contains("Knowledge base result for"),
            "Second turn LLM evaluation should include first-turn KB tool result in context");
    }

    @Test
    void shouldUseKnowledgeBaseBeforeTicketAndCarryContextAcrossTurns() {
        String conversationId = "conv-" + UUID.randomUUID();
        ObjectMapper objectMapper = new ObjectMapper();
        Properties properties = testProperties();

        PersistenceFacade persistenceFacade = DscopePersistenceFactory.create(properties, objectMapper);
        ChatMemoryRepository chatMemoryRepository = DscopeChatMemoryRepositoryFactory.create(properties, objectMapper);

        RouteSelectingGateway gateway = new RouteSelectingGateway(chatMemoryRepository, conversationId, objectMapper);
        SpringAiModelClient modelClient = new SpringAiModelClient(gateway, objectMapper);
        var blueprint = new MarkdownBlueprintLoader().load("classpath:agents/support/agent.md");

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            (toolSpec, arguments, context) -> executeTool(toolSpec.name(), arguments, objectMapper),
            modelClient,
            persistenceFacade,
            new SchemaValidator(),
            objectMapper
        );

        String firstPrompt = "Please search the knowledge base for login troubleshooting guidance";
        String secondPrompt = "Thanks. Please file a support ticket now";
        AgentResponse first = kernel.handleUserMessage(conversationId, firstPrompt);
        AgentResponse second = kernel.handleUserMessage(conversationId, secondPrompt);

        Assertions.assertTrue(gateway.sawKnowledgeBaseInSecondTurn(), "Second turn should include first-turn KB result in evaluation context");

        List<Message> messages = chatMemoryRepository.findByConversationId(conversationId);
        Assertions.assertEquals(4, messages.size());
        Assertions.assertEquals(firstPrompt, messages.get(0).getText());
        Assertions.assertEquals("", messages.get(1).getText());
        Assertions.assertEquals(secondPrompt, messages.get(2).getText());
        Assertions.assertEquals("", messages.get(3).getText());

        List<AgentEvent> auditTrail = persistenceFacade.loadConversation(conversationId, 200);
        Assertions.assertTrue(auditTrail.stream().filter(e -> "user.message".equals(e.type())).count() >= 2);
        Assertions.assertTrue(auditTrail.stream().filter(e -> "tool.start".equals(e.type())).count() >= 2);
        Assertions.assertTrue(auditTrail.stream().filter(e -> "tool.result".equals(e.type())).count() >= 2);
        Assertions.assertTrue(auditTrail.stream().allMatch(e -> e.payload() != null && !e.payload().isNull()));
        Assertions.assertTrue(auditTrail.stream()
            .filter(e -> "tool.start".equals(e.type()))
            .anyMatch(e -> "kb.search".equals(e.payload().path("name").asText())));
        Assertions.assertTrue(auditTrail.stream()
            .filter(e -> "tool.start".equals(e.type()))
            .anyMatch(e -> "support.ticket.open".equals(e.payload().path("name").asText())));
        Assertions.assertTrue(auditTrail.stream()
            .filter(e -> "tool.start".equals(e.type()))
            .anyMatch(e -> e.payload().path("arguments").path("query").asText("").contains("Knowledge base result for")));
        List<String> toolResults = auditTrail.stream()
            .filter(e -> "tool.result".equals(e.type()))
            .map(e -> e.payload().toString())
            .toList();
        Assertions.assertTrue(toolResults.stream().anyMatch(payload -> payload.contains("Knowledge base result for")));
        Assertions.assertTrue(toolResults.stream().anyMatch(payload -> payload.contains("Support ticket created successfully")));
    }

    @Test
    void shouldNotInjectKnowledgeBaseContextWhenTicketIsFirstPrompt() {
        String conversationId = "conv-" + UUID.randomUUID();
        ObjectMapper objectMapper = new ObjectMapper();
        Properties properties = testProperties();

        PersistenceFacade persistenceFacade = DscopePersistenceFactory.create(properties, objectMapper);
        ChatMemoryRepository chatMemoryRepository = DscopeChatMemoryRepositoryFactory.create(properties, objectMapper);

        RouteSelectingGateway gateway = new RouteSelectingGateway(chatMemoryRepository, conversationId, objectMapper);
        SpringAiModelClient modelClient = new SpringAiModelClient(gateway, objectMapper);
        var blueprint = new MarkdownBlueprintLoader().load("classpath:agents/support/agent.md");

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            (toolSpec, arguments, context) -> executeTool(toolSpec.name(), arguments, objectMapper),
            modelClient,
            persistenceFacade,
            new SchemaValidator(),
            objectMapper
        );

        String prompt = "Please file a support ticket for my login issue";
        AgentResponse response = kernel.handleUserMessage(conversationId, prompt);

        Assertions.assertFalse(gateway.sawKnowledgeBaseInSecondTurn(),
            "Gateway should not detect KB context when no KB turn happened before ticket request");

        List<AgentEvent> auditTrail = persistenceFacade.loadConversation(conversationId, 50);
        Assertions.assertTrue(auditTrail.stream()
            .filter(e -> "tool.start".equals(e.type()))
            .anyMatch(e -> "support.ticket.open".equals(e.payload().path("name").asText())));
        Assertions.assertFalse(auditTrail.stream()
            .filter(e -> "tool.start".equals(e.type()))
            .anyMatch(e -> e.payload().path("arguments").path("query").asText("").contains("Knowledge base result for")));
        Assertions.assertTrue(auditTrail.stream()
            .filter(e -> "tool.result".equals(e.type()))
            .map(e -> e.payload().toString())
            .anyMatch(payload -> payload.contains("Support ticket created successfully")));
    }

    private ToolResult executeTool(String toolName, JsonNode arguments, ObjectMapper mapper) {
        String query = arguments == null ? "" : arguments.path("query").asText("");
        if ("kb.search".equals(toolName)) {
            ObjectNode data = mapper.createObjectNode().put("answer", "Knowledge base result for " + query);
            return new ToolResult(data.path("answer").asText(), data, List.of());
        }
        if ("support.ticket.open".equals(toolName)) {
            ObjectNode data = mapper.createObjectNode()
                .put("ticketId", "TCK-" + UUID.randomUUID())
                .put("status", "OPEN")
                .put("summary", query)
                .put("assignedQueue", "L1-SUPPORT")
                .put("message", "Support ticket created successfully");
            return new ToolResult(data.path("message").asText(), data, List.of());
        }
        return new ToolResult("not-used", mapper.nullNode(), List.of());
    }

    private String toolName(List<AgentEvent> events) {
        return events.stream()
            .filter(e -> "tool.start".equals(e.type()))
            .findFirst()
            .map(e -> e.payload().path("name").asText())
            .orElse("");
    }

    private Properties testProperties() {
        Properties properties = new Properties();
        properties.setProperty("camel.persistence.enabled", "true");
        properties.setProperty("camel.persistence.backend", "jdbc");
        properties.setProperty("camel.persistence.jdbc.url", "jdbc:derby:memory:agent-test-" + UUID.randomUUID() + ";create=true");
        properties.setProperty("agent.audit.granularity", "debug");
        return properties;
    }

    private static final class RouteSelectingGateway implements SpringAiChatGateway {

        private static final Pattern KB_RESULT_PATTERN = Pattern.compile("Knowledge base result for[^\\\"]+");
        private final ChatMemoryRepository chatMemoryRepository;
        private final String conversationId;
        private final ObjectMapper objectMapper;
        private boolean sawKnowledgeBaseInSecondTurn;

        private RouteSelectingGateway(ChatMemoryRepository chatMemoryRepository, String conversationId, ObjectMapper objectMapper) {
            this.chatMemoryRepository = chatMemoryRepository;
            this.conversationId = conversationId;
            this.objectMapper = objectMapper;
        }

        @Override
        public SpringAiChatResult generate(String systemPrompt,
                                           String userContext,
                                           List<io.dscope.camel.agent.model.ToolSpec> tools,
                                           String model,
                                           Double temperature,
                                           Integer maxTokens,
                                           java.util.function.Consumer<String> streamingTokenCallback) {
            String userText = extractLastUserMessage(userContext);
            List<AiToolCall> toolCalls = selectTools(userText, userContext);
            String assistantText = "";

            List<Message> existing = new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));
            existing.add(UserMessage.builder().text(userText).build());
            existing.add(new AssistantMessage(assistantText, Map.of(), List.of()));
            chatMemoryRepository.saveAll(conversationId, existing);

            if (streamingTokenCallback != null) {
                streamingTokenCallback.accept(assistantText);
            }
            return new SpringAiChatResult(assistantText, toolCalls, true);
        }

        private List<AiToolCall> selectTools(String userText, String userContext) {
            String normalized = userText == null ? "" : userText.toLowerCase();
            if (normalized.contains("knowledge base") || normalized.contains("kb")) {
                return List.of(new AiToolCall("kb.search", objectMapper.createObjectNode().put("query", userText)));
            }
            if (normalized.contains("ticket") || normalized.contains("file")) {
                sawKnowledgeBaseInSecondTurn = userContext != null && userContext.contains("Knowledge base result for");
                String kbResult = extractKnowledgeBaseResult(userContext);
                String query = sawKnowledgeBaseInSecondTurn
                    ? "Escalate with prior context: " + kbResult
                    : userText;
                return List.of(new AiToolCall("support.ticket.open", objectMapper.createObjectNode().put("query", query)));
            }
            return List.of();
        }

        boolean sawKnowledgeBaseInSecondTurn() {
            return sawKnowledgeBaseInSecondTurn;
        }

        private String extractKnowledgeBaseResult(String userContext) {
            if (userContext == null || userContext.isBlank()) {
                return "";
            }
            Matcher matcher = KB_RESULT_PATTERN.matcher(userContext);
            if (matcher.find()) {
                return matcher.group();
            }
            return "";
        }

        private String extractLastUserMessage(String userContext) {
            if (userContext == null || userContext.isBlank()) {
                return "";
            }
            String[] lines = userContext.split("\\R");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i];
                if (line.startsWith("User: ")) {
                    return toPlainText(line.substring("User: ".length()));
                }
                if (line.startsWith("user.message: ")) {
                    return toPlainText(line.substring("user.message: ".length()));
                }
            }
            return "";
        }

        private String toPlainText(String value) {
            if (value == null) {
                return "";
            }
            String trimmed = value.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
            try {
                JsonNode node = objectMapper.readTree(trimmed);
                return node.isTextual() ? node.asText() : trimmed;
            } catch (Exception ignored) {
                return trimmed;
            }
        }
    }

    private String resolveOpenAiApiKey() {
        String fromEnv = System.getenv("OPENAI_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromSystem = System.getProperty("openai.api.key");
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }

        Properties secrets = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("../.agent-secrets.properties")) {
            if (input != null) {
                secrets.load(input);
                String fromFile = secrets.getProperty("openai.api.key");
                if (fromFile != null && !fromFile.isBlank()) {
                    return fromFile;
                }
            }
        } catch (Exception ignored) {
        }

        try (InputStream input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(".agent-secrets.properties"))) {
            secrets.clear();
            secrets.load(input);
            String fromFile = secrets.getProperty("openai.api.key");
            if (fromFile != null && !fromFile.isBlank()) {
                return fromFile;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static final class CapturingGateway implements SpringAiChatGateway {

        private final SpringAiChatGateway delegate;
        private int calls;
        private String secondTurnContext;

        private CapturingGateway(SpringAiChatGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public SpringAiChatResult generate(String systemPrompt,
                                           String userContext,
                                           List<io.dscope.camel.agent.model.ToolSpec> tools,
                                           String model,
                                           Double temperature,
                                           Integer maxTokens,
                                           java.util.function.Consumer<String> streamingTokenCallback) {
            calls++;
            if (calls == 2) {
                secondTurnContext = userContext;
            }
            return delegate.generate(systemPrompt, userContext, tools, model, temperature, maxTokens, streamingTokenCallback);
        }
    }
}
