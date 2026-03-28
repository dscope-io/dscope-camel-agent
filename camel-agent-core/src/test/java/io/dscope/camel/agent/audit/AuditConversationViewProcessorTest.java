package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class AuditConversationViewProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldRepairMojibakeInAuditConversationViewPayloadAndText() throws Exception {
        InMemoryPersistenceFacade persistenceFacade = new InMemoryPersistenceFacade();
        AuditConversationViewProcessor processor = new AuditConversationViewProcessor(persistenceFacade, MAPPER);
        String mojibake = "O 13:00. Chcel by som sa objednaÅ¥ na vÃ½menu oleja a meno je Roman DobrÃ­k.";

        persistenceFacade.appendEvent(
            new AgentEvent("conv-mojibake", null, "user.message", MAPPER.getNodeFactory().textNode(mojibake), Instant.now()),
            "msg-1"
        );

        var context = new DefaultCamelContext();
        var exchange = new DefaultExchange(context);
        exchange.getMessage().setHeader("conversationId", "conv-mojibake");

        processor.process(exchange);

        JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
        JsonNode message = body.path("agentPerspective").path("messages").get(0);
        String expected = "O 13:00. Chcel by som sa objednať na výmenu oleja a meno je Roman Dobrík.";

        Assertions.assertEquals(expected, message.path("text").asText());
        Assertions.assertEquals(expected, message.path("payload").asText());
    }

    @Test
    void shouldIncludeTranscriptEventsInAgentPerspective() throws Exception {
        InMemoryPersistenceFacade persistenceFacade = new InMemoryPersistenceFacade();
        AuditConversationViewProcessor processor = new AuditConversationViewProcessor(persistenceFacade, MAPPER);

        persistenceFacade.appendEvent(
            new AgentEvent(
                "conv-transcript",
                null,
                "realtime.transcript.final",
                MAPPER.readTree("""
                    {
                      "transcript": "Need an oil change tomorrow at 3pm"
                    }
                    """),
                Instant.now()
            ),
            "evt-1"
        );
        persistenceFacade.appendEvent(
            new AgentEvent(
                "conv-transcript",
                null,
                "response.audio_transcript.done",
                MAPPER.readTree("""
                    {
                      "payload": {
                        "transcript": "I found availability tomorrow at 3pm."
                      }
                    }
                    """),
                Instant.now()
            ),
            "evt-2"
        );

        var context = new DefaultCamelContext();
        var exchange = new DefaultExchange(context);
        exchange.getMessage().setHeader("conversationId", "conv-transcript");

        processor.process(exchange);

        JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
        JsonNode messages = body.path("agentPerspective").path("messages");

        Assertions.assertEquals(2, messages.size());
        Assertions.assertEquals(List.of("user", "assistant"), List.of(
            messages.get(0).path("role").asText(),
            messages.get(1).path("role").asText()
        ));
        Assertions.assertEquals("Need an oil change tomorrow at 3pm", messages.get(0).path("text").asText());
        Assertions.assertEquals("I found availability tomorrow at 3pm.", messages.get(1).path("text").asText());
    }

    @Test
    void shouldIncludeAgentAndPlanMetadataInConversationSteps() throws Exception {
        InMemoryPersistenceFacade persistenceFacade = new InMemoryPersistenceFacade();
        AgentPlanSelectionResolver resolver = new AgentPlanSelectionResolver(persistenceFacade, MAPPER);
        AuditConversationViewProcessor processor = new AuditConversationViewProcessor(
            persistenceFacade,
            MAPPER,
            resolver,
            "classpath:runtime/test-agents.yaml",
            "classpath:agents/valid-agent.md"
        );

        persistenceFacade.appendEvent(
            resolver.selectionEvent(
                "conv-plan",
                resolver.resolve("conv-plan", "support", "v2", "classpath:runtime/test-agents.yaml", "classpath:agents/valid-agent.md")
            ),
            "evt-1"
        );
        persistenceFacade.appendEvent(
            new AgentEvent("conv-plan", null, "agent.message", MAPPER.getNodeFactory().textNode("Hello from v2"), Instant.now()),
            "evt-2"
        );
        persistenceFacade.appendEvent(
            new AgentEvent(
                "conv-plan",
                null,
                "model.usage",
                MAPPER.readTree("""
                    {
                      "provider": "openai",
                      "model": "gpt-5.4",
                      "apiMode": "chat",
                      "promptTokens": 13,
                      "completionTokens": 5,
                      "totalTokens": 18
                    }
                    """),
                Instant.now()
            ),
            "evt-3"
        );

        var context = new DefaultCamelContext();
        var exchange = new DefaultExchange(context);
        exchange.getMessage().setHeader("conversationId", "conv-plan");

        processor.process(exchange);

        JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
        JsonNode message = body.path("agentPerspective").path("messages").get(0);

        Assertions.assertEquals("support", message.path("agent").path("planName").asText());
        Assertions.assertEquals("v2", message.path("agent").path("planVersion").asText());
        Assertions.assertEquals("SupportAssistant", message.path("agent").path("agentName").asText());
        Assertions.assertEquals("0.3.0", message.path("agent").path("agentVersion").asText());
        Assertions.assertEquals("openai", message.path("agent").path("ai").path("provider").asText());
        Assertions.assertEquals("gpt-5.4-mini", message.path("agent").path("ai").path("model").asText());
        Assertions.assertEquals("SupportAssistant", body.path("agent").path("agentName").asText());
        Assertions.assertEquals("0.3.0", body.path("agent").path("agentVersion").asText());
        Assertions.assertEquals("responses-http", body.path("agent").path("ai").path("properties").path("agent.runtime.spring-ai.openai.api-mode").asText());
        Assertions.assertEquals("gpt-5.4-mini", body.path("agent").path("ai").path("model").asText());
        Assertions.assertEquals(18, body.path("modelUsage").path("totals").path("totalTokens").asInt());
    }
}
