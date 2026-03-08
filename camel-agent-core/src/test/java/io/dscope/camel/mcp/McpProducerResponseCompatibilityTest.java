package io.dscope.camel.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class McpProducerResponseCompatibilityTest {

    @Test
    void shouldAllowArrayRootToolsCallResponseViaMcpClient() throws Exception {
        try (CamelContext context = new DefaultCamelContext()) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:mock-mcp").setBody(constant("[{\"name\":\"customerLookup\",\"phone\":\"421222200096\"}]"));
                }
            });
            context.start();

            ProducerTemplate template = context.createProducerTemplate();
            JsonNode result = McpClient.callResultJson(
                template,
                "mcp:camel:direct:mock-mcp",
                "tools/call",
                Map.of("name", "customerLookup", "arguments", Map.of("phone", "421222200096"))
            );

            Assertions.assertNotNull(result);
            Assertions.assertTrue(result.isArray());
            Assertions.assertEquals("customerLookup", result.get(0).path("name").asText());
            Assertions.assertEquals("421222200096", result.get(0).path("phone").asText());
        }
    }
}
