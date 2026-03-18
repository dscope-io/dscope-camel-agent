package io.dscope.camel.agent.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class A2AExposedAgentCatalogLoaderTest {

    private final A2AExposedAgentCatalogLoader loader = new A2AExposedAgentCatalogLoader();

    @TempDir
    Path tempDir;

    @Test
    void loadsAndValidatesExposedAgentsCatalog() throws Exception {
        Path config = tempDir.resolve("a2a-agents.yaml");
        Files.writeString(config, """
            agents:
              - agentId: support-public
                name: Support Public Agent
                description: Handles support conversations
                defaultAgent: true
                version: 1.0.0
                planName: support
                planVersion: v1
                skills:
                  - support
                  - troubleshooting
              - agentId: billing-public
                name: Billing Public Agent
                description: Handles billing
                planName: billing
                planVersion: v2
            """);

        A2AExposedAgentCatalog catalog = loader.load(config.toString());

        assertEquals(2, catalog.agents().size());
        assertEquals("support-public", catalog.defaultAgent().getAgentId());
        assertEquals("billing", catalog.requireAgent("billing-public").getPlanName());
        assertTrue(catalog.requireAgent("").isDefaultAgent());
    }

    @Test
    void rejectsCatalogWithoutDefaultAgent() throws Exception {
        Path config = tempDir.resolve("a2a-agents-no-default.yaml");
        Files.writeString(config, """
            agents:
              - agentId: support-public
                name: Support Public Agent
                planName: support
                planVersion: v1
            """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> loader.load(config.toString()));
        assertTrue(error.getMessage().contains("default"));
    }
}
