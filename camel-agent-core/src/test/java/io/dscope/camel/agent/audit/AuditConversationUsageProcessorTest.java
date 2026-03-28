package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.time.Instant;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AuditConversationUsageProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldReturnUsageTotalsAndBreakdownForConversation() throws Exception {
        InMemoryPersistenceFacade persistenceFacade = new InMemoryPersistenceFacade();
        AuditConversationUsageProcessor processor = new AuditConversationUsageProcessor(persistenceFacade, MAPPER);

        persistenceFacade.appendEvent(
            new AgentEvent(
                "conv-usage-api",
                null,
                "model.usage",
                MAPPER.readTree("""
                    {
                      "provider": "openai",
                      "model": "gpt-5.4",
                      "apiMode": "chat",
                      "promptTokens": 20,
                      "completionTokens": 10,
                      "totalTokens": 30,
                      "totalCostUsd": 0.00015000,
                      "currency": "USD"
                    }
                    """),
                Instant.now()
            ),
            "evt-1"
        );

        var context = new DefaultCamelContext();
        var exchange = new DefaultExchange(context);
        exchange.getMessage().setHeader("conversationId", "conv-usage-api");

        processor.process(exchange);

        JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
        Assertions.assertEquals("conv-usage-api", body.path("conversationId").asText());
        Assertions.assertEquals(1, body.path("modelUsage").path("callCount").asInt());
        Assertions.assertEquals(30, body.path("modelUsage").path("totals").path("totalTokens").asInt());
        Assertions.assertEquals("gpt-5.4", body.path("modelUsage").path("byModel").get(0).path("model").asText());
    }
}