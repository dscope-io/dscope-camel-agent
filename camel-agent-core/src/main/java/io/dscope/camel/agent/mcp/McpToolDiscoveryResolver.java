package io.dscope.camel.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.mcp.McpClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McpToolDiscoveryResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolDiscoveryResolver.class);

    private McpToolDiscoveryResolver() {
    }

    public static AgentBlueprint resolve(AgentBlueprint blueprint, ProducerTemplate producerTemplate, ObjectMapper mapper) {
        List<ToolSpec> resolvedTools = new ArrayList<>();
        List<JsonNode> catalogs = new ArrayList<>();
        Set<String> seenToolNames = new HashSet<>();

        for (ToolSpec toolSpec : blueprint.tools()) {
            if (!isMcpEndpoint(toolSpec.endpointUri())) {
                if (seenToolNames.add(toolSpec.name())) {
                    resolvedTools.add(toolSpec);
                }
                continue;
            }

            try {
                LOGGER.debug("MCP discovery started: serviceTool={}, endpoint={}",
                    toolSpec.name(),
                    toolSpec.endpointUri());
                JsonNode resultNode = McpClient.toolsListResultJson(producerTemplate, toolSpec.endpointUri());
                catalogs.add(mapper.createObjectNode()
                    .put("serviceTool", toolSpec.name())
                    .put("endpointUri", toolSpec.endpointUri())
                    .set("result", resultNode));

                List<ToolSpec> discoveredTools = toDiscoveredTools(toolSpec, resultNode);
                LOGGER.info("MCP discovery completed: serviceTool={}, endpoint={}, discoveredTools={}",
                    toolSpec.name(),
                    toolSpec.endpointUri(),
                    discoveredTools.size());
                if (discoveredTools.isEmpty()) {
                    LOGGER.warn("MCP discovery returned no tools: serviceTool={}, endpoint={}",
                        toolSpec.name(),
                        toolSpec.endpointUri());
                    if (seenToolNames.add(toolSpec.name())) {
                        resolvedTools.add(toolSpec);
                    }
                    continue;
                }
                for (ToolSpec discoveredTool : discoveredTools) {
                    if (seenToolNames.add(discoveredTool.name())) {
                        resolvedTools.add(discoveredTool);
                    }
                }
            } catch (Exception e) {
                catalogs.add(mapper.createObjectNode()
                    .put("serviceTool", toolSpec.name())
                    .put("endpointUri", toolSpec.endpointUri())
                    .put("error", e.getMessage()));
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                String rootMessage = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
                LOGGER.warn("MCP discovery failed: serviceTool={}, endpoint={}, reason={}",
                    toolSpec.name(),
                    toolSpec.endpointUri(),
                    rootMessage);
                LOGGER.debug("MCP discovery failure details", e);
                if (seenToolNames.add(toolSpec.name())) {
                    resolvedTools.add(toolSpec);
                }
            }
        }

        return new AgentBlueprint(
            blueprint.name(),
            blueprint.version(),
            blueprint.systemInstruction(),
            List.copyOf(resolvedTools),
            blueprint.jsonRouteTemplates(),
            List.copyOf(catalogs),
            blueprint.realtime(),
            blueprint.aguiPreRun(),
            blueprint.resources()
        );
    }

    private static List<ToolSpec> toDiscoveredTools(ToolSpec sourceTool, JsonNode toolsListResult) {
        JsonNode toolsNode = toolsListResult.path("tools");
        if (!toolsNode.isArray()) {
            if (toolsListResult.isArray()) {
                toolsNode = toolsListResult;
            } else {
                return List.of();
            }
        }

        List<ToolSpec> discovered = new ArrayList<>();
        for (JsonNode toolNode : toolsNode) {
            String name = text(toolNode, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            String description = text(toolNode, "description");
            JsonNode inputSchema = toolNode.path("inputSchema");
            if (inputSchema.isMissingNode()) {
                inputSchema = null;
            }
            JsonNode outputSchema = toolNode.path("outputSchema");
            if (outputSchema.isMissingNode()) {
                outputSchema = null;
            }
            ToolPolicy policy = sourceTool.policy() != null ? sourceTool.policy() : new ToolPolicy(false, 0, 1000);
            ToolSpec discoveredTool = new ToolSpec(
                name,
                description,
                null,
                sourceTool.endpointUri(),
                inputSchema,
                outputSchema,
                policy
            );
            discovered.add(mergeLocalSchemaOverride(sourceTool, discoveredTool));
        }
        return discovered;
    }

    static ToolSpec mergeLocalSchemaOverride(ToolSpec sourceTool, ToolSpec discoveredTool) {
        if (sourceTool == null || discoveredTool == null || !sourceTool.name().equals(discoveredTool.name())) {
            return discoveredTool;
        }
        JsonNode inputSchema = sourceTool.inputSchema() == null ? discoveredTool.inputSchema() : sourceTool.inputSchema();
        JsonNode outputSchema = sourceTool.outputSchema() == null ? discoveredTool.outputSchema() : sourceTool.outputSchema();
        if (inputSchema == discoveredTool.inputSchema() && outputSchema == discoveredTool.outputSchema()) {
            return discoveredTool;
        }
        return new ToolSpec(
            discoveredTool.name(),
            discoveredTool.description(),
            discoveredTool.routeId(),
            discoveredTool.endpointUri(),
            inputSchema,
            outputSchema,
            discoveredTool.policy()
        );
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean isMcpEndpoint(String endpointUri) {
        return endpointUri != null && endpointUri.startsWith("mcp:");
    }
}
