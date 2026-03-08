package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentPlanSelectionResolverTest {

    @Test
    void shouldLoadValidCatalog() {
        AgentPlanCatalog catalog = new AgentPlanCatalogLoader().load("classpath:runtime/test-agents.yaml");

        Assertions.assertEquals("support", catalog.defaultPlan().name());
        Assertions.assertEquals("v1", catalog.defaultVersion("support").version());
        Assertions.assertEquals("classpath:agents/valid-agent-with-realtime.md", catalog.requireVersion("support", "v2").blueprint());
    }

    @Test
    void shouldRejectMultipleDefaultPlans() {
        IllegalArgumentException thrown = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new AgentPlanCatalogLoader().load("classpath:runtime/test-agents-invalid.yaml")
        );

        Assertions.assertTrue(thrown.getMessage().contains("exactly one default plan"));
    }

    @Test
    void shouldResolveDefaultsAndStickySelection() {
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();
        AgentPlanSelectionResolver resolver = new AgentPlanSelectionResolver(persistence, new ObjectMapper());

        ResolvedAgentPlan first = resolver.resolve("conv-1", null, null, "classpath:runtime/test-agents.yaml", "classpath:agents/valid-agent.md");
        Assertions.assertEquals("support", first.planName());
        Assertions.assertEquals("v1", first.planVersion());
        Assertions.assertTrue(first.selectionPersistRequired());

        persistence.appendEvent(resolver.selectionEvent("conv-1", first), "id-1");

        ResolvedAgentPlan sticky = resolver.resolve("conv-1", null, null, "classpath:runtime/test-agents.yaml", "classpath:agents/valid-agent.md");
        Assertions.assertEquals("support", sticky.planName());
        Assertions.assertEquals("v1", sticky.planVersion());
        Assertions.assertFalse(sticky.selectionPersistRequired());

        ResolvedAgentPlan override = resolver.resolve("conv-1", "support", "v2", "classpath:runtime/test-agents.yaml", "classpath:agents/valid-agent.md");
        Assertions.assertEquals("v2", override.planVersion());
        Assertions.assertTrue(override.selectionPersistRequired());
    }

    @Test
    void shouldFallbackToLegacyBlueprintWithoutCatalog() {
        AgentPlanSelectionResolver resolver = new AgentPlanSelectionResolver(new InMemoryPersistenceFacade(), new ObjectMapper());

        ResolvedAgentPlan resolved = resolver.resolve("conv-legacy", null, null, "", "classpath:agents/valid-agent.md");

        Assertions.assertTrue(resolved.legacyMode());
        Assertions.assertEquals("classpath:agents/valid-agent.md", resolved.blueprint());
    }
}
