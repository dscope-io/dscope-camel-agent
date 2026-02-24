package io.dscope.camel.agent.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.AgentKernel;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.api.BlueprintLoader;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.api.ToolRegistry;
import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.executor.TemplateAwareCamelToolExecutor;
import io.dscope.camel.agent.kernel.DefaultAgentKernel;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.kernel.StaticAiModelClient;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.RealtimeSpec;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import io.dscope.camel.agent.registry.DefaultToolRegistry;
import io.dscope.camel.agent.runtime.RealtimeConfigResolver;
import io.dscope.camel.agent.validation.SchemaValidator;
import io.dscope.camel.mcp.McpClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.camel.Endpoint;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.support.DefaultComponent;

@Component("agent")
public class AgentComponent extends DefaultComponent {

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        AgentEndpoint endpoint = new AgentEndpoint(uri, this);
        endpoint.setAgentId(remaining);
        setProperties(endpoint, parameters);

        BlueprintLoader blueprintLoader = new MarkdownBlueprintLoader();
        AgentBlueprint loadedBlueprint = applyRealtimeFallback(blueprintLoader.load(endpoint.getBlueprint()));

        ObjectMapper mapper = findRegistry(ObjectMapper.class).orElseGet(ObjectMapper::new);
        ProducerTemplate producerTemplate = getCamelContext().createProducerTemplate();
        AgentBlueprint blueprint = resolveMcpTools(loadedBlueprint, producerTemplate, mapper);

        ToolRegistry toolRegistry = findRegistry(ToolRegistry.class)
            .orElseGet(() -> new DefaultToolRegistry(blueprint.tools()));
        PersistenceFacade persistenceFacade = findRegistry(PersistenceFacade.class).orElseGet(InMemoryPersistenceFacade::new);
        ToolExecutor toolExecutor = findRegistry(ToolExecutor.class)
            .orElseGet(() -> new TemplateAwareCamelToolExecutor(getCamelContext(), producerTemplate, mapper, blueprint.jsonRouteTemplates()));
        AiModelClient aiModelClient = findRegistry(AiModelClient.class).orElseGet(StaticAiModelClient::new);
        CorrelationRegistry correlationRegistry = findRegistry(CorrelationRegistry.class).orElseGet(CorrelationRegistry::global);

        AgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            toolRegistry,
            toolExecutor,
            aiModelClient,
            persistenceFacade,
            new SchemaValidator(),
            mapper
        );

        endpoint.setAgentKernel(kernel);
        endpoint.setCorrelationRegistry(correlationRegistry);
        return endpoint;
    }

    private <T> Optional<T> findRegistry(Class<T> type) {
        return Optional.ofNullable(getCamelContext().getRegistry().findSingleByType(type));
    }

    private AgentBlueprint resolveMcpTools(AgentBlueprint blueprint, ProducerTemplate producerTemplate, ObjectMapper mapper) {
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
                JsonNode resultNode = McpClient.toolsListResultJson(producerTemplate, toolSpec.endpointUri());
                catalogs.add(mapper.createObjectNode()
                    .put("serviceTool", toolSpec.name())
                    .put("endpointUri", toolSpec.endpointUri())
                    .set("result", resultNode));

                List<ToolSpec> discoveredTools = toDiscoveredTools(toolSpec, resultNode);
                if (discoveredTools.isEmpty()) {
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
            blueprint.aguiPreRun()
        );
    }

    private List<ToolSpec> toDiscoveredTools(ToolSpec sourceTool, JsonNode toolsListResult) {
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
            discovered.add(new ToolSpec(
                name,
                description,
                null,
                sourceTool.endpointUri(),
                inputSchema,
                outputSchema,
                policy
            ));
        }
        return discovered;
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private boolean isMcpEndpoint(String endpointUri) {
        return endpointUri != null && endpointUri.startsWith("mcp:");
    }

    private AgentBlueprint applyRealtimeFallback(AgentBlueprint blueprint) {
        RealtimeSpec resolvedRealtime = RealtimeConfigResolver.resolve(blueprint.realtime(), this::runtimeProperty);
        if (Objects.equals(resolvedRealtime, blueprint.realtime())) {
            return blueprint;
        }
        return new AgentBlueprint(
            blueprint.name(),
            blueprint.version(),
            blueprint.systemInstruction(),
            blueprint.tools(),
            blueprint.jsonRouteTemplates(),
            blueprint.mcpToolCatalogs(),
            resolvedRealtime,
            blueprint.aguiPreRun()
        );
    }

    private String runtimeProperty(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            String value = getCamelContext().resolvePropertyPlaceholders("{{" + key + ":}}"
            );
            if (value == null || value.isBlank()) {
                return null;
            }
            if (value.contains("{{") && value.contains("}}")) {
                return null;
            }
            return value.trim();
        } catch (Exception ignored) {
            return null;
        }
    }
}
