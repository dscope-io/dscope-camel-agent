package io.dscope.camel.agent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.a2a.catalog.AgentCardCatalog;
import io.dscope.camel.a2a.catalog.AgentCardPolicyChecker;
import io.dscope.camel.a2a.catalog.AgentCardSignatureVerifier;
import io.dscope.camel.a2a.catalog.AgentCardSigner;
import io.dscope.camel.a2a.catalog.AllowAllAgentCardPolicyChecker;
import io.dscope.camel.a2a.catalog.AllowAllAgentCardSignatureVerifier;
import io.dscope.camel.a2a.catalog.DefaultAgentCardCatalog;
import io.dscope.camel.a2a.catalog.NoopAgentCardSigner;
import io.dscope.camel.a2a.model.AgentCard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentA2AAgentCardCatalog implements AgentCardCatalog {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final A2AExposedAgentCatalog exposedAgentCatalog;
    private final String endpointUrl;
    private final AgentCardSigner signer;
    private final AgentCardSignatureVerifier verifier;
    private final AgentCardPolicyChecker policyChecker;

    public AgentA2AAgentCardCatalog(A2AExposedAgentCatalog exposedAgentCatalog, String endpointUrl) {
        this(exposedAgentCatalog, endpointUrl, new NoopAgentCardSigner(), new AllowAllAgentCardSignatureVerifier(), new AllowAllAgentCardPolicyChecker());
    }

    public AgentA2AAgentCardCatalog(A2AExposedAgentCatalog exposedAgentCatalog,
                                    String endpointUrl,
                                    AgentCardSigner signer,
                                    AgentCardSignatureVerifier verifier,
                                    AgentCardPolicyChecker policyChecker) {
        this.exposedAgentCatalog = exposedAgentCatalog;
        this.endpointUrl = endpointUrl;
        this.signer = signer == null ? new NoopAgentCardSigner() : signer;
        this.verifier = verifier == null ? new AllowAllAgentCardSignatureVerifier() : verifier;
        this.policyChecker = policyChecker == null ? new AllowAllAgentCardPolicyChecker() : policyChecker;
    }

    @Override
    public AgentCard getDiscoveryCard() {
        return enrich(baseCatalog().getDiscoveryCard(), false);
    }

    @Override
    public AgentCard getExtendedCard() {
        return enrich(baseCatalog().getExtendedCard(), true);
    }

    @Override
    public String getCardSignature(AgentCard card) {
        return baseCatalog().getCardSignature(card);
    }

    private DefaultAgentCardCatalog baseCatalog() {
        A2AExposedAgentSpec defaultAgent = exposedAgentCatalog.defaultAgent();
        return new DefaultAgentCardCatalog(
            defaultAgent.getAgentId(),
            defaultAgent.getName(),
            defaultAgent.getDescription(),
            endpointUrl,
            signer,
            verifier,
            policyChecker
        );
    }

    private AgentCard enrich(AgentCard card, boolean extended) {
        A2AExposedAgentSpec defaultAgent = exposedAgentCatalog.defaultAgent();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (card.getMetadata() != null && !card.getMetadata().isEmpty()) {
            metadata.putAll(card.getMetadata());
        }
        metadata.put("discovery", true);
        metadata.put("extended", extended);
        metadata.put("defaultAgentId", defaultAgent.getAgentId());
        metadata.put("agents", exposedAgentCatalog.agents().stream().map(this::cardMetadata).toList());
        card.setVersion(defaultVersion(defaultAgent));
        card.setMetadata(metadata);
        return card;
    }

    private Map<String, Object> cardMetadata(A2AExposedAgentSpec spec) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentId", spec.getAgentId());
        data.put("name", spec.getName());
        data.put("description", spec.getDescription() == null ? "" : spec.getDescription());
        data.put("version", defaultVersion(spec));
        data.put("default", spec.isDefaultAgent());
        data.put("planName", spec.getPlanName());
        data.put("planVersion", spec.getPlanVersion());
        data.put("skills", spec.getSkills());
        if (!spec.getMetadata().isEmpty()) {
            data.put("metadata", spec.getMetadata());
        }
        return data;
    }

    private String defaultVersion(A2AExposedAgentSpec spec) {
        if (spec.getVersion() != null && !spec.getVersion().isBlank()) {
            return spec.getVersion();
        }
        return spec.getPlanVersion();
    }
}
