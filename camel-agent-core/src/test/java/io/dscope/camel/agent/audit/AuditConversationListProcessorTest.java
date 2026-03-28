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

class AuditConversationListProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldExposeResolvedAiAtTopLevelForListItems() throws Exception {
        InMemoryPersistenceFacade persistenceFacade = new InMemoryPersistenceFacade();
        AgentPlanSelectionResolver resolver = new AgentPlanSelectionResolver(persistenceFacade, MAPPER);
        AuditConversationListProcessor processor = new AuditConversationListProcessor(
            persistenceFacade,
            MAPPER,
            resolver,
            "classpath:runtime/test-agents.yaml",
            "classpath:agents/valid-agent.md"
        );

        persistenceFacade.appendEvent(
            resolver.selectionEvent(
                "conv-list",
                resolver.resolve("conv-list", "support", "v2", "classpath:runtime/test-agents.yaml", "classpath:agents/valid-agent.md")
            ),
            "evt-1"
        );
        persistenceFacade.appendEvent(
            new AgentEvent("conv-list", null, "agent.message", MAPPER.getNodeFactory().textNode("Listed output"), Instant.now()),
            "evt-2"
        );

        var context = new DefaultCamelContext();
        var exchange = new DefaultExchange(context);
        exchange.getMessage().setHeader("limit", 10);

        processor.process(exchange);

        JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
        JsonNode item = body.path("items").get(0);
        Assertions.assertEquals("conv-list", item.path("conversationId").asText());
        Assertions.assertEquals("openai", item.path("ai").path("provider").asText());
        Assertions.assertEquals("gpt-5.4-mini", item.path("ai").path("model").asText());
        Assertions.assertEquals("responses-http", item.path("metadata").path("ai").path("properties").path("agent.runtime.spring-ai.openai.api-mode").asText());
    }
}