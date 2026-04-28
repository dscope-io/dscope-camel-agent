package io.dscope.camel.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class McpToolDiscoveryResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldPreserveLocalSchemaOverrideForMatchingDiscoveredTool() {
        var localOutputSchema = objectMapper.createObjectNode()
            .put("type", "object");
        localOutputSchema.putObject("properties")
            .putObject("status")
            .put("type", "string");

        var discoveredOutputSchema = objectMapper.createObjectNode()
            .put("type", "object");
        discoveredOutputSchema.putArray("required")
            .add("status")
            .add("calendarId");

        ToolPolicy policy = new ToolPolicy(false, 0, 1000);
        ToolSpec source = new ToolSpec(
            "calendar.bookAppointment",
            "local booking override",
            null,
            "mcp:https://calendar.example/mcp",
            null,
            localOutputSchema,
            policy);
        ToolSpec discovered = new ToolSpec(
            "calendar.bookAppointment",
            "discovered booking tool",
            null,
            "mcp:https://calendar.example/mcp",
            null,
            discoveredOutputSchema,
            policy);

        ToolSpec merged = McpToolDiscoveryResolver.mergeLocalSchemaOverride(source, discovered);

        Assertions.assertSame(localOutputSchema, merged.outputSchema());
        Assertions.assertEquals("discovered booking tool", merged.description());
        Assertions.assertEquals("mcp:https://calendar.example/mcp", merged.endpointUri());
    }

    @Test
    void shouldKeepDiscoveredSchemaForDifferentToolName() {
        var localOutputSchema = objectMapper.createObjectNode().put("type", "object");
        var discoveredOutputSchema = objectMapper.createObjectNode().put("type", "object");

        ToolPolicy policy = new ToolPolicy(false, 0, 1000);
        ToolSpec source = new ToolSpec(
            "calendar.bookAppointment",
            "local booking override",
            null,
            "mcp:https://calendar.example/mcp",
            null,
            localOutputSchema,
            policy);
        ToolSpec discovered = new ToolSpec(
            "calendar.searchAppointments",
            "discovered search tool",
            null,
            "mcp:https://calendar.example/mcp",
            null,
            discoveredOutputSchema,
            policy);

        ToolSpec merged = McpToolDiscoveryResolver.mergeLocalSchemaOverride(source, discovered);

        Assertions.assertSame(discoveredOutputSchema, merged.outputSchema());
    }
}
