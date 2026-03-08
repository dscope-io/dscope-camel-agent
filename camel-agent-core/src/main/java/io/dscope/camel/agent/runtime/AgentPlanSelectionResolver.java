package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AgentPlanSelectionResolver {

    private static final int HISTORY_SCAN_LIMIT = 500;

    private final PersistenceFacade persistenceFacade;
    private final AgentPlanCatalogLoader catalogLoader;
    private final ObjectMapper objectMapper;
    private final Map<String, AgentPlanCatalog> catalogCache = new ConcurrentHashMap<>();

    public AgentPlanSelectionResolver(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
        this(persistenceFacade, new AgentPlanCatalogLoader(), objectMapper);
    }

    public AgentPlanSelectionResolver(PersistenceFacade persistenceFacade,
                                      AgentPlanCatalogLoader catalogLoader,
                                      ObjectMapper objectMapper) {
        this.persistenceFacade = persistenceFacade;
        this.catalogLoader = catalogLoader;
        this.objectMapper = objectMapper;
    }

    public void clearCache() {
        catalogCache.clear();
    }

    public AgentPlanCatalog loadCatalog(String plansConfig) {
        if (plansConfig == null || plansConfig.isBlank()) {
            throw new IllegalArgumentException("agents-config location is required");
        }
        return catalogCache.computeIfAbsent(plansConfig.trim(), catalogLoader::load);
    }

    public ResolvedAgentPlan resolve(String conversationId,
                                     String requestedPlanName,
                                     String requestedPlanVersion,
                                     String plansConfig,
                                     String legacyBlueprint) {
        if (plansConfig == null || plansConfig.isBlank()) {
            if (legacyBlueprint == null || legacyBlueprint.isBlank()) {
                throw new IllegalArgumentException("Either agent.agents-config or agent.blueprint must be configured");
            }
            return ResolvedAgentPlan.legacy(legacyBlueprint);
        }

        AgentPlanCatalog catalog = loadCatalog(plansConfig);
        StickySelection sticky = stickySelection(conversationId);

        boolean explicitPlan = requestedPlanName != null && !requestedPlanName.isBlank();
        boolean explicitVersion = requestedPlanVersion != null && !requestedPlanVersion.isBlank();

        String planName = explicitPlan
            ? requestedPlanName.trim()
            : sticky.planName() != null && !sticky.planName().isBlank()
                ? sticky.planName()
                : catalog.defaultPlan().name();

        AgentPlanSpec plan = catalog.requirePlan(planName);

        String version = explicitVersion
            ? requestedPlanVersion.trim()
            : !explicitPlan && sticky.matchesPlan(planName) && sticky.planVersion() != null && !sticky.planVersion().isBlank()
                ? sticky.planVersion()
                : catalog.defaultVersion(planName).version();

        AgentPlanVersionSpec versionSpec = catalog.requireVersion(planName, version);
        boolean persistRequired = sticky.differsFrom(planName, version, versionSpec.blueprint());
        return new ResolvedAgentPlan(planName, version, versionSpec.blueprint(), explicitPlan, explicitVersion, persistRequired, false);
    }

    public AgentEvent selectionEvent(String conversationId, ResolvedAgentPlan resolvedPlan) {
        JsonNode payload = objectMapper.valueToTree(Map.of(
            "planName", resolvedPlan.planName(),
            "planVersion", resolvedPlan.planVersion(),
            "blueprint", resolvedPlan.blueprint(),
            "selectedAt", Instant.now().toString()
        ));
        return new AgentEvent(conversationId, null, "conversation.plan.selected", payload, Instant.now());
    }

    public String resolveBlueprintForConversation(String conversationId, String plansConfig, String legacyBlueprint) {
        return resolve(conversationId, null, null, plansConfig, legacyBlueprint).blueprint();
    }

    private StickySelection stickySelection(String conversationId) {
        if (persistenceFacade == null || conversationId == null || conversationId.isBlank()) {
            return StickySelection.NONE;
        }
        List<AgentEvent> events = persistenceFacade.loadConversation(conversationId, HISTORY_SCAN_LIMIT);
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent event = events.get(i);
            if (event == null || !"conversation.plan.selected".equals(event.type()) || event.payload() == null || event.payload().isNull()) {
                continue;
            }
            String planName = text(event.payload(), "planName");
            String planVersion = text(event.payload(), "planVersion");
            String blueprint = firstNonBlank(text(event.payload(), "blueprint"), text(event.payload(), "blueprintUri"));
            if (!planName.isBlank() || !planVersion.isBlank() || !blueprint.isBlank()) {
                return new StickySelection(planName, planVersion, blueprint);
            }
        }
        return StickySelection.NONE;
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
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

    private record StickySelection(String planName, String planVersion, String blueprint) {
        private static final StickySelection NONE = new StickySelection("", "", "");

        boolean matchesPlan(String candidate) {
            return candidate != null && candidate.equals(planName);
        }

        boolean differsFrom(String candidatePlan, String candidateVersion, String candidateBlueprint) {
            return !matches(planName, candidatePlan) || !matches(planVersion, candidateVersion) || !matches(blueprint, candidateBlueprint);
        }

        private boolean matches(String left, String right) {
            return (left == null || left.isBlank() ? "" : left).equals(right == null || right.isBlank() ? "" : right);
        }
    }
}
