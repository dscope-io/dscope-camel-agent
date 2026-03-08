package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.runtime.AgentPlanCatalog;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.agent.runtime.AgentPlanSpec;
import io.dscope.camel.agent.runtime.AgentPlanVersionSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class AuditAgentCatalogProcessor implements Processor {

    private final ObjectMapper objectMapper;
    private final AgentPlanSelectionResolver planSelectionResolver;
    private final String plansConfig;
    private final String blueprintUri;

    public AuditAgentCatalogProcessor(ObjectMapper objectMapper,
                                      AgentPlanSelectionResolver planSelectionResolver,
                                      String plansConfig,
                                      String blueprintUri) {
        this.objectMapper = objectMapper;
        this.planSelectionResolver = planSelectionResolver;
        this.plansConfig = plansConfig == null || plansConfig.isBlank() ? null : plansConfig;
        this.blueprintUri = blueprintUri == null || blueprintUri.isBlank() ? null : blueprintUri;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("plansConfig", plansConfig == null ? "" : plansConfig);
        response.put("legacyBlueprint", blueprintUri == null ? "" : blueprintUri);

        if (plansConfig == null || plansConfig.isBlank() || planSelectionResolver == null) {
            AuditMetadataSupport.BlueprintMetadata metadata = AuditMetadataSupport.loadBlueprintMetadata(blueprintUri);
            response.put("defaultPlan", "legacy");
            response.put("plans", List.of(Map.of(
                "name", "legacy",
                "default", true,
                "versions", List.of(Map.of(
                    "version", "legacy",
                    "default", true,
                    "blueprint", blueprintUri == null ? "" : blueprintUri,
                    "agentName", metadata.agentTitle(),
                    "agentVersion", metadata.agentVersion()
                ))
            )));
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
            return;
        }

        AgentPlanCatalog catalog = planSelectionResolver.loadCatalog(plansConfig);
        response.put("defaultPlan", catalog.defaultPlan().name());
        response.put("plans", catalog.plans().stream().map(this::toPlan).toList());

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private Map<String, Object> toPlan(AgentPlanSpec plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", plan.name());
        data.put("default", plan.defaultPlan());
        data.put("versions", plan.versions().stream().map(this::toVersion).toList());
        return data;
    }

    private Map<String, Object> toVersion(AgentPlanVersionSpec version) {
        AuditMetadataSupport.BlueprintMetadata metadata = AuditMetadataSupport.loadBlueprintMetadata(version.blueprint());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", version.version());
        data.put("default", version.defaultVersion());
        data.put("blueprint", version.blueprint());
        data.put("agentName", metadata.agentTitle());
        data.put("agentVersion", metadata.agentVersion());
        return data;
    }
}
