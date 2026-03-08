package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AuditAgentCatalogProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldExposePlanCatalogWithDefaultsAndVersions() throws Exception {
        AuditAgentCatalogProcessor processor = new AuditAgentCatalogProcessor(
            MAPPER,
            new AgentPlanSelectionResolver(new InMemoryPersistenceFacade(), MAPPER),
            "classpath:runtime/test-agents.yaml",
            "classpath:agents/valid-agent.md"
        );

        var context = new DefaultCamelContext();
        var exchange = new DefaultExchange(context);

        processor.process(exchange);

        JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
        Assertions.assertEquals("support", body.path("defaultPlan").asText());
        Assertions.assertEquals(2, body.path("plans").size());
        Assertions.assertEquals("support", body.path("plans").get(0).path("name").asText());
        Assertions.assertTrue(body.path("plans").get(0).path("default").asBoolean());
        Assertions.assertEquals("v1", body.path("plans").get(0).path("versions").get(0).path("version").asText());
        Assertions.assertTrue(body.path("plans").get(0).path("versions").get(0).path("default").asBoolean());
    }
}
