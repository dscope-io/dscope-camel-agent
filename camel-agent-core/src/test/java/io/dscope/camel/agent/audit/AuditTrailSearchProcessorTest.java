package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import java.time.Instant;
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

        var context = new DefaultCamelContext();
        var exchange = new DefaultExchange(context);
        exchange.getMessage().setHeader("conversationId", "conv-audit");

        processor.process(exchange);

        JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
        JsonNode projected = body.path("events").get(1);

        Assertions.assertEquals("support", projected.path("agent").path("planName").asText());
        Assertions.assertEquals("v2", projected.path("agent").path("planVersion").asText());
        Assertions.assertEquals("SupportAssistant", projected.path("agent").path("agentName").asText());
        Assertions.assertEquals("0.3.0", projected.path("agent").path("agentVersion").asText());
        Assertions.assertEquals("0.3.0", body.path("conversationMetadata").path("agentVersion").asText());
    }
}
