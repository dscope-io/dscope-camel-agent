package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.agui.AgentAgUiPreRunTextProcessor;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.realtime.RealtimeBrowserSessionInitProcessor;
import io.dscope.camel.agent.realtime.RealtimeEventProcessor;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.spi.PropertiesComponent;

public class RuntimeResourceRefreshProcessor implements Processor {

    private static final String DEFAULT_BLUEPRINT = "classpath:agents/support/agent.md";

    private final String applicationYamlPath;
    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;
    private final MarkdownBlueprintLoader blueprintLoader;
    private volatile Properties lastAppliedProperties;

    public RuntimeResourceRefreshProcessor(String applicationYamlPath,
                                           PersistenceFacade persistenceFacade,
                                           ObjectMapper objectMapper) {
        this.applicationYamlPath = applicationYamlPath;
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
        this.blueprintLoader = new MarkdownBlueprintLoader();
        this.lastAppliedProperties = initialProperties(applicationYamlPath);
    }

    @Override
    public void process(Exchange exchange) {
        try {
            Properties previous = copy(lastAppliedProperties);
            Properties refreshed = RuntimeResourceBootstrapper.resolve(loadEffectiveProperties());
            applyProperties(exchange, refreshed);

            String blueprintUri = refreshed.getProperty("agent.blueprint", DEFAULT_BLUEPRINT);
            AgentBlueprint blueprint = blueprintLoader.load(blueprintUri);
            int purposeMaxChars = intProperty(refreshed,
                240,
                "agent.runtime.realtime.agent-profile-purpose-max-chars",
                "agent.runtime.realtime.agentProfilePurposeMaxChars",
                "agent.realtime.agent-profile-purpose-max-chars",
                "agent.realtime.agentProfilePurposeMaxChars");

            clearCachedBlueprints(exchange);
            String requestedConversationId = requestedConversationId(exchange);
            Set<String> conversationIds = resolveTargetConversationIds(requestedConversationId);
            int persisted = appendRefreshEvents(conversationIds, blueprint, blueprintUri);
            int realtimeSynced = syncRealtimeContext(exchange, blueprint, purposeMaxChars);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("refreshed", true);
            body.put("at", Instant.now().toString());
            body.put("blueprint", blueprintUri);
            body.put("agentName", blueprint.name());
            body.put("agentVersion", blueprint.version());
            body.put("conversationScope", requestedConversationId.isBlank() ? "all" : "single");
            body.put("conversationTargetCount", conversationIds.size());
            body.put("conversationEventsSynced", persisted);
            body.put("chatMemorySynced", 0);
            body.put("realtimeContextSynced", realtimeSynced);

            ObjectNode resources = objectMapper.createObjectNode();
            resources.put("routesIncludePattern", refreshed.getProperty("agent.runtime.routes-include-pattern", ""));
            resources.put("kameletLocation", refreshed.getProperty("camel.component.kamelet.location", ""));
            resources.put("blueprintChanged", changed(previous, refreshed, "agent.blueprint"));
            resources.put("routesChanged", changed(previous, refreshed, "agent.runtime.routes-include-pattern"));
            resources.put("kameletsChanged", changed(previous, refreshed, "camel.component.kamelet.location"));
            body.set("resources", resources);

            body.put("requiresRouteRestart", resources.path("routesChanged").asBoolean(false));

            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setHeader("Content-Type", "application/json");
            exchange.getMessage().setBody(body.toString());
        } catch (Exception refreshFailure) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("refreshed", false);
            error.put("error", refreshFailure.getMessage() == null
                ? refreshFailure.getClass().getSimpleName()
                : refreshFailure.getMessage());
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 500);
            exchange.getMessage().setHeader("Content-Type", "application/json");
            exchange.getMessage().setBody(error.toString());
        }
    }

    private Properties loadEffectiveProperties() throws Exception {
        Properties effective = RuntimePropertyPlaceholderResolver.resolve(ApplicationYamlLoader.loadFromClasspath(applicationYamlPath));
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("agent.") || key.startsWith("camel.")) {
                effective.setProperty(key, System.getProperty(key));
            }
        }
        return effective;
    }

    private Properties initialProperties(String yamlPath) {
        try {
            return RuntimeResourceBootstrapper.resolve(RuntimePropertyPlaceholderResolver.resolve(ApplicationYamlLoader.loadFromClasspath(yamlPath)));
        } catch (Exception ignored) {
            return new Properties();
        }
    }

    private void applyProperties(Exchange exchange, Properties refreshed) {
        PropertiesComponent propertiesComponent = exchange.getContext().getPropertiesComponent();
        Properties applied = copy(refreshed);
        propertiesComponent.setInitialProperties(applied);
        for (String key : applied.stringPropertyNames()) {
            exchange.getContext().getGlobalOptions().put(key, applied.getProperty(key));
        }
        lastAppliedProperties = copy(applied);
    }

    private Properties copy(Properties source) {
        Properties copy = new Properties();
        if (source != null) {
            copy.putAll(source);
        }
        return copy;
    }

    private boolean changed(Properties previous, Properties current, String key) {
        String oldValue = previous == null ? null : previous.getProperty(key);
        String newValue = current == null ? null : current.getProperty(key);
        if (oldValue == null && newValue == null) {
            return false;
        }
        if (oldValue == null || newValue == null) {
            return true;
        }
        return !oldValue.equals(newValue);
    }

    private void clearCachedBlueprints(Exchange exchange) {
        AgentAgUiPreRunTextProcessor agUiPreRun = exchange.getContext().getRegistry()
            .findSingleByType(AgentAgUiPreRunTextProcessor.class);
        if (agUiPreRun != null) {
            agUiPreRun.clearBlueprintCache();
        }

        RealtimeBrowserSessionInitProcessor realtimeInit = exchange.getContext().getRegistry()
            .lookupByNameAndType("supportRealtimeSessionInitProcessor", RealtimeBrowserSessionInitProcessor.class);
        if (realtimeInit != null) {
            realtimeInit.clearBlueprintCache();
        }

        RealtimeEventProcessor realtimeEvent = exchange.getContext().getRegistry()
            .lookupByNameAndType("supportRealtimeEventProcessorCore", RealtimeEventProcessor.class);
        if (realtimeEvent != null) {
            realtimeEvent.clearBlueprintRealtimeCache();
        }
    }

    private Set<String> resolveTargetConversationIds(String requestedConversationId) {
        if (!requestedConversationId.isBlank()) {
            return Set.of(requestedConversationId);
        }

        return collectConversationIds();
    }

    private String requestedConversationId(Exchange exchange) {
        return firstNonBlank(
            exchange.getMessage().getHeader("conversationId", String.class),
            exchange.getMessage().getHeader("conversation-id", String.class),
            bodyConversationId(exchange)
        );
    }

    private Set<String> collectConversationIds() {
        Set<String> conversationIds = new LinkedHashSet<>();
        if (persistenceFacade != null) {
            conversationIds.addAll(persistenceFacade.listConversationIds(1000));
        }
        return conversationIds;
    }

    private String bodyConversationId(Exchange exchange) {
        try {
            String raw = exchange.getMessage().getBody(String.class);
            if (raw == null || raw.isBlank()) {
                return "";
            }
            JsonNode root = objectMapper.readTree(raw);
            String id = text(root, "conversationId");
            if (id.isBlank()) {
                id = text(root, "conversation-id");
            }
            return id;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private int appendRefreshEvents(Set<String> conversationIds, AgentBlueprint blueprint, String blueprintUri) {
        if (persistenceFacade == null || conversationIds == null || conversationIds.isEmpty()) {
            return 0;
        }
        int updated = 0;
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("blueprint", blueprintUri);
        payload.put("agentName", blueprint.name());
        payload.put("agentVersion", blueprint.version());
        payload.put("refreshedAt", Instant.now().toString());

        for (String conversationId : conversationIds) {
            if (conversationId == null || conversationId.isBlank()) {
                continue;
            }
            persistenceFacade.appendEvent(
                new AgentEvent(conversationId, null, "agent.definition.refreshed", payload.deepCopy(), Instant.now()),
                UUID.randomUUID().toString()
            );
            updated++;
        }
        return updated;
    }

    private int syncRealtimeContext(Exchange exchange, AgentBlueprint blueprint, int purposeMaxChars) {
        RealtimeBrowserSessionInitProcessor initProcessor = exchange.getContext().getRegistry()
            .lookupByNameAndType("supportRealtimeSessionInitProcessor", RealtimeBrowserSessionInitProcessor.class);
        if (initProcessor == null) {
            return 0;
        }
        String requestedConversationId = requestedConversationId(exchange);
        if (!requestedConversationId.isBlank()) {
            return initProcessor.refreshAgentProfileForConversation(requestedConversationId, blueprint, purposeMaxChars);
        }
        return initProcessor.refreshAgentProfileForAllSessions(blueprint, purposeMaxChars);
    }

    private int intProperty(Properties properties, int defaultValue, String... keys) {
        for (String key : keys) {
            String value = properties.getProperty(key);
            if (value != null && !value.isBlank()) {
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }
}
