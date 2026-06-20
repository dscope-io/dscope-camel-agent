package io.dscope.camel.agent.agui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgUiA2uiResponseNormalizationProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldNormalizeSurfacePayloadInsideSseDataBlock() throws Exception {
        AgUiA2uiResponseNormalizationProcessor processor = new AgUiA2uiResponseNormalizationProcessor(MAPPER);

        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setBody("""
                event: message
                data: {"surfaceId":"support-ticket-123","data":{"ticketId":"TCK-42","summary":"Login failed","message":"Structured ticket created"}}
                """);

            processor.process(exchange);

            String normalized = exchange.getMessage().getBody(String.class);
            String dataLine = normalized.lines()
                .filter(line -> line.startsWith("data: "))
                .findFirst()
                .orElseThrow();
            var root = MAPPER.readTree(dataLine.substring("data: ".length()));
            Assertions.assertTrue(normalized.contains("event: message"));
            Assertions.assertTrue(root.has("components"));
            Assertions.assertEquals("support-ticket-123", root.path("surfaceId").asText());
            Assertions.assertEquals("TCK-42", root.path("components").get(0).path("data").path("ticketId").asText());
            Assertions.assertFalse(root.has("data"));
        }
    }
}
