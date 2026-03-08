package io.dscope.camel.agent.component;

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
import io.dscope.camel.agent.mcp.McpToolDiscoveryResolver;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.RealtimeSpec;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import io.dscope.camel.agent.registry.DefaultToolRegistry;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.agent.runtime.RealtimeConfigResolver;
import io.dscope.camel.agent.runtime.ResolvedAgentPlan;
import io.dscope.camel.agent.validation.SchemaValidator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.support.DefaultComponent;

@Component("agent")
public class AgentComponent extends DefaultComponent {

    private final Map<String, AgentKernel> kernelCache = new ConcurrentHashMap<>();

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        AgentEndpoint endpoint = new AgentEndpoint(uri, this);
        endpoint.setAgentId(remaining);
        setProperties(endpoint, parameters);
        CorrelationRegistry correlationRegistry = findRegistry(CorrelationRegistry.class).orElseGet(CorrelationRegistry::global);
        endpoint.setCorrelationRegistry(correlationRegistry);
        return endpoint;
    }

    public void clearKernelCache() {
        kernelCache.clear();
    }

    public ResolvedAgentPlan resolvePlan(AgentEndpoint endpoint, Exchange exchange, String conversationId) {
        String requestedPlanName = exchange.getMessage().getHeader("agent.planName", String.class);
        String requestedPlanVersion = exchange.getMessage().getHeader("agent.planVersion", String.class);
        return planResolver().resolve(
            conversationId,
            requestedPlanName,
            requestedPlanVersion,
            resolvePlansConfig(endpoint),
            resolveLegacyBlueprint(endpoint)
        );
    }

    public AgentKernel resolveKernel(AgentEndpoint endpoint, ResolvedAgentPlan resolvedPlan) {
        String blueprintLocation = resolvedPlan == null || resolvedPlan.blueprint() == null || resolvedPlan.blueprint().isBlank()
            ? resolveLegacyBlueprint(endpoint)
            : resolvedPlan.blueprint();
        String cacheKey = (resolvedPlan == null || resolvedPlan.legacyMode())
            ? "legacy|" + blueprintLocation
            : resolvedPlan.planName() + "|" + resolvedPlan.planVersion() + "|" + blueprintLocation;
        return kernelCache.computeIfAbsent(cacheKey, ignored -> buildKernel(blueprintLocation));
    }

    public AgentPlanSelectionResolver planResolver() {
        return findRegistry(AgentPlanSelectionResolver.class)
            .orElseGet(() -> new AgentPlanSelectionResolver(persistenceFacade(), objectMapper()));
    }

    public PersistenceFacade persistenceFacade() {
        return findRegistry(PersistenceFacade.class).orElseGet(InMemoryPersistenceFacade::new);
    }

    public String resolvePlansConfig(AgentEndpoint endpoint) {
        String configured = endpoint == null ? null : endpoint.getPlansConfig();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return runtimeProperty("agent.agents-config");
    }

    public String resolveLegacyBlueprint(AgentEndpoint endpoint) {
        String configured = endpoint == null ? null : endpoint.getBlueprint();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return defaultIfBlank(runtimeProperty("agent.blueprint"), "classpath:agents/support/agent.md");
    }

    public ObjectMapper objectMapper() {
        return findRegistry(ObjectMapper.class).orElseGet(ObjectMapper::new);
    }

    private <T> Optional<T> findRegistry(Class<T> type) {
        return Optional.ofNullable(getCamelContext().getRegistry().findSingleByType(type));
    }

    private AgentKernel buildKernel(String blueprintLocation) {
        BlueprintLoader blueprintLoader = new MarkdownBlueprintLoader();
        AgentBlueprint loadedBlueprint = applyRealtimeFallback(blueprintLoader.load(blueprintLocation));

        ObjectMapper mapper = objectMapper();
        ProducerTemplate producerTemplate = getCamelContext().createProducerTemplate();
        AgentBlueprint blueprint = McpToolDiscoveryResolver.resolve(loadedBlueprint, producerTemplate, mapper);

        ToolRegistry toolRegistry = new DefaultToolRegistry(blueprint.tools());
        ToolExecutor toolExecutor = createToolExecutor(producerTemplate, mapper, blueprint);
        AiModelClient aiModelClient = findRegistry(AiModelClient.class).orElseGet(StaticAiModelClient::new);
        PersistenceFacade persistenceFacade = persistenceFacade();

        return new DefaultAgentKernel(
            blueprint,
            toolRegistry,
            toolExecutor,
            aiModelClient,
            persistenceFacade,
            new SchemaValidator(),
            mapper
        );
    }

    private ToolExecutor createToolExecutor(ProducerTemplate producerTemplate,
                                            ObjectMapper objectMapper,
                                            AgentBlueprint blueprint) {
        return findRegistry(ToolExecutor.class)
            .orElseGet(() -> new TemplateAwareCamelToolExecutor(
                getCamelContext(),
                producerTemplate,
                objectMapper,
                defaultIfNull(blueprint.jsonRouteTemplates(), List.of())
            ));
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

    private <T> T defaultIfNull(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
