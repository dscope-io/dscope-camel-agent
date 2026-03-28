package io.dscope.camel.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class McpToolDiscoveryResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldApplyMatchingConcreteToolSchemaOverrideDuringDiscovery() throws Exception {
        ToolSpec sourceTool = new ToolSpec(
            "calendar.bookAppointment",
            "Local booking override",
            null,
            "mcp:https://calendar.example/mcp",
            MAPPER.readTree("""
                {
                  "type": "object",
                  "required": ["provider", "start", "end", "summary"]
                }
                """),
            MAPPER.readTree("""
                {
                  "type": "object",
                  "required": ["status", "eventId", "start", "end"]
                }
                """),
            new ToolPolicy(false, 0, 1000)
        );

        JsonNode toolsListResult = MAPPER.readTree("""
            {
              "tools": [
                {
                  "name": "calendar.bookAppointment",
                  "description": "Remote booking schema",
                  "inputSchema": {"type": "object", "required": ["provider"]},
                  "outputSchema": {"type": "object", "required": ["status", "calendarId", "eventId"]}
                },
                {
                  "name": "calendar.listAvailability",
                  "description": "Remote availability schema",
                  "inputSchema": {"type": "object", "required": ["provider", "from", "to"]},
                  "outputSchema": {"type": "object", "required": ["provider", "availability"]}
                }
              ]
            }
            """);

        Method method = McpToolDiscoveryResolver.class.getDeclaredMethod("toDiscoveredTools", ToolSpec.class, JsonNode.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<ToolSpec> discovered = (List<ToolSpec>) method.invoke(null, sourceTool, toolsListResult);

        ToolSpec bookingTool = discovered.stream()
            .filter(tool -> "calendar.bookAppointment".equals(tool.name()))
            .findFirst()
            .orElseThrow();
        ToolSpec availabilityTool = discovered.stream()
            .filter(tool -> "calendar.listAvailability".equals(tool.name()))
            .findFirst()
            .orElseThrow();

        Assertions.assertEquals("Local booking override", bookingTool.description());
        Assertions.assertEquals(
            List.of("provider", "start", "end", "summary"),
            jsonTextList(bookingTool.inputSchema().path("required"))
        );
        Assertions.assertEquals(
            List.of("status", "eventId", "start", "end"),
            jsonTextList(bookingTool.outputSchema().path("required"))
        );
        Assertions.assertEquals(
            List.of("provider", "from", "to"),
            jsonTextList(availabilityTool.inputSchema().path("required"))
        );
        Assertions.assertEquals(
            List.of("provider", "availability"),
            jsonTextList(availabilityTool.outputSchema().path("required"))
        );
    }

    @Test
    void shouldKeepConcreteOverrideWhenLaterMcpServiceDiscoversSameToolName() throws Exception {
        ToolSpec explicitBookingOverride = new ToolSpec(
            "calendar.bookAppointment",
            "Local booking override",
            null,
            "mcp:https://calendar.example/mcp",
            MAPPER.readTree("""
                {
                  "type": "object",
                  "required": ["provider", "start", "end", "summary"]
                }
                """),
            MAPPER.readTree("""
                {
                  "type": "object",
                  "required": ["status", "eventId", "start", "end"]
                }
                """),
            new ToolPolicy(false, 0, 1000)
        );
        ToolSpec serviceBridge = new ToolSpec(
            "calendar.mcp",
            "Calendar service bridge",
            null,
            "mcp:https://calendar.example/mcp",
            null,
            null,
            new ToolPolicy(false, 0, 1000)
        );

        JsonNode toolsListResult = MAPPER.readTree("""
            {
              "tools": [
                {
                  "name": "calendar.bookAppointment",
                  "description": "Remote booking schema",
                  "inputSchema": {"type": "object", "required": ["provider"]},
                  "outputSchema": {"type": "object", "required": ["status", "calendarId", "eventId"]}
                },
                {
                  "name": "calendar.listAvailability",
                  "description": "Remote availability schema",
                  "inputSchema": {"type": "object", "required": ["provider", "from", "to"]},
                  "outputSchema": {"type": "object", "required": ["provider", "availability"]}
                }
              ]
            }
            """);

        List<ToolSpec> resolvedTools = new ArrayList<>();
        Set<String> seenToolNames = new HashSet<>();
        for (ToolSpec sourceTool : List.of(explicitBookingOverride, serviceBridge)) {
            for (ToolSpec discoveredTool : invokeDiscovered(sourceTool, toolsListResult)) {
                if (seenToolNames.add(discoveredTool.name())) {
                    resolvedTools.add(discoveredTool);
                }
            }
        }

        AgentBlueprint resolvedBlueprint = new AgentBlueprint(
            "calendar",
            "v2",
            "system",
            resolvedTools,
            List.of(),
            List.of(),
            null,
            null,
            List.of()
        );

        ToolSpec bookingTool = resolvedBlueprint.tools().stream()
            .filter(tool -> "calendar.bookAppointment".equals(tool.name()))
            .findFirst()
            .orElseThrow();

        Assertions.assertEquals(2, resolvedBlueprint.tools().size());
        Assertions.assertEquals("Local booking override", bookingTool.description());
        Assertions.assertEquals(
            List.of("status", "eventId", "start", "end"),
            jsonTextList(bookingTool.outputSchema().path("required"))
        );
    }

    @SuppressWarnings("unchecked")
    private static List<ToolSpec> invokeDiscovered(ToolSpec sourceTool, JsonNode toolsListResult) throws Exception {
        Method method = McpToolDiscoveryResolver.class.getDeclaredMethod("toDiscoveredTools", ToolSpec.class, JsonNode.class);
        method.setAccessible(true);
        return (List<ToolSpec>) method.invoke(null, sourceTool, toolsListResult);
    }

    private static List<String> jsonTextList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
            .map(JsonNode::asText)
            .toList();
    }
}