package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import java.time.Instant;
import java.util.Map;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AuditTrailSearchProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldIncludeAgentMetadataOnProjectedAuditEvents() throws Exception {
        InMemoryPersistenceFacade persistenceFacade = new InMemoryPersistenceFacade();
        AgentPlanSelectionResolver resolver = new AgentPlanSelectionResolver(persistenceFacade, MAPPER);
        AuditTrailSearchProcessor processor = new AuditTrailSearchProcessor(
            persistenceFacade,
            MAPPER,
            resolver,
            "classpath:runtime/test-agents.yaml",
            "classpath:agents/valid-agent.md"
        );

        persistenceFacade.appendEvent(
            resolver.selectionEvent(
                "conv-audit",
                resolver.resolve("conv-audit", "support", "v2", "classpath:runtime/test-agents.yaml", "classpath:agents/valid-agent.md")
            ),
            "evt-1"
        );
        persistenceFacade.appendEvent(
            new AgentEvent("conv-audit", null, "agent.message", MAPPER.getNodeFactory().textNode("Projected output"), Instant.now()),
            "evt-2"
        );
        persistenceFacade.appendEvent(
            new AgentEvent(
                "conv-audit",
                null,
                "model.usage",
                MAPPER.readTree("""
                    {
                      "provider": "openai",
                      "model": "gpt-5.4",
                      "apiMode": "chat",
                      "promptTokens": 11,
                      "completionTokens": 7,
                      "totalTokens": 18,
                      "totalCostUsd": 0.00012345,
                      "currency": "USD"
                    }
                    """),
                Instant.now()
            ),
            "evt-3"
        );

        var context = new DefaultCamelContext();
        var exchange = new DefaultExchange(context);
        exchange.getMessage().setHeader("conversationId", "conv-audit");

        processor.process(exchange);

        JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
        JsonNode projected = body.path("events").get(1);

        Assertions.assertEquals("openai", body.path("ai").path("provider").asText());
        Assertions.assertEquals("gpt-5.4-mini", body.path("ai").path("model").asText());
        Assertions.assertEquals("support", projected.path("agent").path("planName").asText());
        Assertions.assertEquals("v2", projected.path("agent").path("planVersion").asText());
        Assertions.assertEquals("SupportAssistant", projected.path("agent").path("agentName").asText());
        Assertions.assertEquals("0.3.0", projected.path("agent").path("agentVersion").asText());
        Assertions.assertEquals("0.3.0", body.path("conversationMetadata").path("agentVersion").asText());
        Assertions.assertEquals(18, body.path("modelUsage").path("totals").path("totalTokens").asInt());
        Assertions.assertEquals("gpt-5.4", body.path("modelUsage").path("byModel").get(0).path("model").asText());
    }

    @Test
    void shouldAggregateChainEventsByRootConversationId() throws Exception {
        InMemoryPersistenceFacade persistenceFacade = new InMemoryPersistenceFacade();
        AgentPlanSelectionResolver resolver = new AgentPlanSelectionResolver(persistenceFacade, MAPPER);
        AuditTrailSearchProcessor processor = new AuditTrailSearchProcessor(
            persistenceFacade,
            MAPPER,
            resolver,
            "classpath:runtime/test-agents.yaml",
            "classpath:agents/valid-agent.md"
        );

        persistenceFacade.appendEvent(
            new AgentEvent(
                "conv-chain-a",
                null,
                "conversation.a2a.outbound.completed",
                MAPPER.valueToTree(Map.of(
                    "_correlation", Map.of(
                        "a2aRootConversationId", "root-chain-1",
                        "a2aParentConversationId", "origin-1"
                    )
                )),
                Instant.parse("2026-04-21T12:00:00Z")
            ),
            "evt-chain-a1"
        );
        persistenceFacade.appendEvent(
            new AgentEvent("conv-chain-a", null, "agent.message", MAPPER.getNodeFactory().textNode("A event"), Instant.parse("2026-04-21T12:00:01Z")),
            "evt-chain-a2"
        );
        persistenceFacade.appendEvent(
            new AgentEvent(
                "conv-chain-b",
                null,
                "conversation.a2a.outbound.completed",
                MAPPER.valueToTree(Map.of(
                    "_correlation", Map.of(
                        "a2aRootConversationId", "root-chain-1",
                        "a2aParentConversationId", "conv-chain-a"
                    )
                )),
                Instant.parse("2026-04-21T12:00:02Z")
            ),
            "evt-chain-b1"
        );
        persistenceFacade.appendEvent(
            new AgentEvent("conv-chain-b", null, "agent.message", MAPPER.getNodeFactory().textNode("B event"), Instant.parse("2026-04-21T12:00:03Z")),
            "evt-chain-b2"
        );

        var context = new DefaultCamelContext();
        var exchange = new DefaultExchange(context);
        exchange.getMessage().setHeader("rootConversationId", "root-chain-1");

        processor.process(exchange);

        JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
        Assertions.assertEquals("root-chain-1", body.path("rootConversationId").asText());
        Assertions.assertEquals(2, body.path("conversationCount").asInt());
        Assertions.assertEquals(4, body.path("count").asInt());
        Assertions.assertEquals(2, body.path("chainSummary").path("conversationCount").asInt());
        Assertions.assertEquals("conv-chain-a", body.path("chainSummary").path("entryConversationId").asText());
        Assertions.assertEquals("root-chain-1", body.path("hops").get(0).path("rootConversationId").asText());
        Assertions.assertEquals("conv-chain-b", body.path("hops").get(0).path("children").get(0).asText());
        Assertions.assertEquals("conv-chain-a", body.path("conversations").get(0).path("conversationId").asText());
        Assertions.assertEquals("conv-chain-b", body.path("conversations").get(1).path("conversationId").asText());
        Assertions.assertEquals(2, body.path("exports").path("graph").path("nodes").size());
        Assertions.assertEquals(1, body.path("exports").path("graph").path("edges").size());
        Assertions.assertTrue(body.path("exports").path("csv").path("text").asText().contains("root-chain-1"));
    }
}
