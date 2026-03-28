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
        Assertions.assertEquals("openai", catalog.requirePlan("support").ai().provider());
        Assertions.assertEquals("responses-http", catalog.requirePlan("support").ai().properties().get("agent.runtime.spring-ai.openai.api-mode"));
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
        Assertions.assertEquals("openai", first.ai().provider());
        Assertions.assertEquals("responses-http", first.ai().properties().get("agent.runtime.spring-ai.openai.api-mode"));

        persistence.appendEvent(resolver.selectionEvent("conv-1", first), "id-1");

        ResolvedAgentPlan sticky = resolver.resolve("conv-1", null, null, "classpath:runtime/test-agents.yaml", "classpath:agents/valid-agent.md");
        Assertions.assertEquals("support", sticky.planName());
        Assertions.assertEquals("v1", sticky.planVersion());
        Assertions.assertFalse(sticky.selectionPersistRequired());

        ResolvedAgentPlan override = resolver.resolve("conv-1", "support", "v2", "classpath:runtime/test-agents.yaml", "classpath:agents/valid-agent.md");
        Assertions.assertEquals("v2", override.planVersion());
        Assertions.assertTrue(override.selectionPersistRequired());
        Assertions.assertEquals("gpt-5.4-mini", override.ai().model());
        Assertions.assertEquals(512, override.ai().maxTokens());
        Assertions.assertEquals("true", override.ai().properties().get("agent.runtime.spring-ai.openai.prompt-cache.enabled"));

        var selectionEvent = resolver.selectionEvent("conv-1", override);
        Assertions.assertEquals("gpt-5.4-mini", selectionEvent.payload().path("ai").path("model").asText());
    }

    @Test
    void shouldFallbackToLegacyBlueprintWithoutCatalog() {
        AgentPlanSelectionResolver resolver = new AgentPlanSelectionResolver(new InMemoryPersistenceFacade(), new ObjectMapper());

        ResolvedAgentPlan resolved = resolver.resolve("conv-legacy", null, null, "", "classpath:agents/valid-agent.md");

        Assertions.assertTrue(resolved.legacyMode());
        Assertions.assertEquals("classpath:agents/valid-agent.md", resolved.blueprint());
    }
}
