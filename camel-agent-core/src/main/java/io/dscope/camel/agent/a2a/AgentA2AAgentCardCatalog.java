package io.dscope.camel.agent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.a2a.catalog.AgentCardCatalog;
import io.dscope.camel.a2a.catalog.AgentCardPolicyChecker;
import io.dscope.camel.a2a.catalog.AgentCardSignatureVerifier;
import io.dscope.camel.a2a.catalog.AgentCardSigner;
import io.dscope.camel.a2a.catalog.AllowAllAgentCardPolicyChecker;
import io.dscope.camel.a2a.catalog.AllowAllAgentCardSignatureVerifier;
import io.dscope.camel.a2a.catalog.NoopAgentCardSigner;
import io.dscope.camel.a2a.config.A2AProtocolMethods;
import io.dscope.camel.a2a.model.AgentCapabilities;
import io.dscope.camel.a2a.model.AgentCard;
import io.dscope.camel.a2a.model.AgentSecurityScheme;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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
        AgentCard card = baseCard(false);
        policyChecker.validate(card);
        return card;
    }

    @Override
    public AgentCard getExtendedCard() {
        AgentCard card = baseCard(true);
        policyChecker.validate(card);
        return card;
    }

    @Override
    public String getCardSignature(AgentCard card) {
        try {
            String canonical = objectMapper.writeValueAsString(card);
            String signature = signer.sign(canonical);
            if (signature != null && !verifier.verify(canonical, signature)) {
                throw new IllegalStateException("Agent card signature verification failed");
            }
            return signature;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign A2A agent card", e);
        }
    }

    private AgentCard baseCard(boolean extended) {
        A2AExposedAgentSpec defaultAgent = exposedAgentCatalog.defaultAgent();

        AgentCapabilities capabilities = new AgentCapabilities();
        capabilities.setStreaming(true);
        capabilities.setPushNotifications(true);
        capabilities.setStatefulTasks(true);
        capabilities.setSupportedMethods(List.copyOf(new TreeSet<>(A2AProtocolMethods.CORE_METHODS)));

        AgentSecurityScheme bearer = new AgentSecurityScheme();
        bearer.setType("http");
        bearer.setScheme("bearer");
        bearer.setDescription("Bearer token authentication");
        bearer.setScopes(List.of("a2a.read", "a2a.write"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("discovery", true);
        metadata.put("extended", extended);
        metadata.put("defaultAgentId", defaultAgent.getAgentId());
        metadata.put("agents", exposedAgentCatalog.agents().stream().map(this::cardMetadata).toList());

        AgentCard card = new AgentCard();
        card.setAgentId(defaultAgent.getAgentId());
        card.setName(defaultAgent.getName());
        card.setDescription(defaultAgent.getDescription());
        card.setEndpointUrl(endpointUrl);
        card.setVersion(defaultVersion(defaultAgent));
        card.setCapabilities(capabilities);
        card.setSecuritySchemes(Map.of("bearerAuth", bearer));
        card.setDefaultInputModes(List.of("application/json", "text/plain"));
        card.setDefaultOutputModes(List.of("application/json", "text/event-stream"));
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
