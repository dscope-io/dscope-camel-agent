package io.dscope.camel.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.config.AgentHeaders;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

class TicketLifecycleProcessorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsTicketIdStableAcrossConversationLifecycle() throws Exception {
        TicketLifecycleProcessor processor = new TicketLifecycleProcessor(objectMapper);

        try (DefaultCamelContext context = new DefaultCamelContext()) {
            context.start();

            JsonNode created = invoke(processor, context, "child-conv-1", Map.of("query", "Please open a support ticket for login errors"));
            JsonNode updated = invoke(processor, context, "child-conv-1", Map.of("query", "Please update the ticket with my latest screenshot"));
            JsonNode closed = invoke(processor, context, "child-conv-1", Map.of("query", "Please close the ticket now"));

            assertEquals(created.path("ticketId").asText(), updated.path("ticketId").asText());
            assertEquals(created.path("ticketId").asText(), closed.path("ticketId").asText());
            assertEquals("OPEN", created.path("status").asText());
            assertEquals("IN_PROGRESS", updated.path("status").asText());
            assertEquals("CLOSED", closed.path("status").asText());
            assertTrue(updated.path("notifyClient").asBoolean());
            assertTrue(closed.path("notifyClient").asBoolean());
        }
    }

    private JsonNode invoke(TicketLifecycleProcessor processor,
                            DefaultCamelContext context,
                            String conversationId,
                            Map<String, Object> body) throws Exception {
        Exchange exchange = new DefaultExchange(context);
        exchange.getIn().setHeader(AgentHeaders.CONVERSATION_ID, conversationId);
        exchange.getIn().setBody(body);
        processor.process(exchange);
        return objectMapper.readTree(exchange.getMessage().getBody(String.class));
    }
}
