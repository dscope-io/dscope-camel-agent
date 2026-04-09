package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.telephony.onboarding.OpenAiTwilioSipOnboardingService;
import io.dscope.camel.agent.telephony.onboarding.TelephonyOnboardingPersistenceService;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

final class SupportTelephonyOnboardingProcessor implements Processor {

    private final ObjectMapper objectMapper;
    private final OpenAiTwilioSipOnboardingService onboardingService;

    SupportTelephonyOnboardingProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.onboardingService = new OpenAiTwilioSipOnboardingService(objectMapper);
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ObjectNode request = normalizeRequest(exchange);
        ObjectNode resolved = resolveRequest(exchange, request);
        ObjectNode response = onboardingService.buildPlan(resolved);

        String tenantId = response.path("tenantId").asText("default");
        String agentId = response.path("agentId").asText("default");
        String configConversationId = TelephonyOnboardingPersistenceService.conversationId(tenantId, agentId);
        boolean persist = !resolved.path("persist").isBoolean() || resolved.path("persist").asBoolean(true);
        boolean persisted = false;

        PersistenceFacade persistenceFacade = exchange.getContext().getRegistry().lookupByNameAndType("persistenceFacade", PersistenceFacade.class);
        if (persist && persistenceFacade != null) {
            TelephonyOnboardingPersistenceService store = new TelephonyOnboardingPersistenceService(persistenceFacade, objectMapper);
            ObjectNode persistedConfig = object(response, "persistedConfig");
            persistedConfig.put("valid", response.path("valid").asBoolean(false));
            persistedConfig.set("errors", response.path("errors").deepCopy());
            persistedConfig.set("warnings", response.path("warnings").deepCopy());
            store.save(configConversationId, persistedConfig);
            persisted = true;
        }

        response.put("persisted", persisted);
        response.put("retrievalUrl", "/telephony/onboarding/openai-twilio?tenantId=" + tenantId + "&agentId=" + agentId);

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private ObjectNode resolveRequest(Exchange exchange, ObjectNode request) {
        ObjectNode resolved = request.deepCopy();
        ObjectNode openAi = ensureObject(resolved, "openai");
        ObjectNode twilio = ensureObject(resolved, "twilio");

        putIfBlank(resolved, "tenantId", property(exchange, "agent.runtime.telephony.onboarding.tenant-id", "default"));
        putIfBlank(resolved, "agentId", property(exchange, "agent.runtime.telephony.onboarding.agent-id", "default"));
        putIfBlank(resolved, "displayName", property(exchange, "camel.main.name", resolved.path("agentId").asText("agent")));
        putIfBlank(resolved, "publicBaseUrl", defaultPublicBaseUrl(exchange));

        putIfBlank(openAi, "projectId", firstNonBlank(
            text(openAi, "projectId"),
            property(exchange, "agent.runtime.telephony.openai.project-id", ""),
            System.getenv("OPENAI_PROJECT_ID"),
            System.getProperty("openai.project.id")
        ));
        putIfBlank(openAi, "model", property(exchange, "agent.runtime.realtime.model", "gpt-realtime"));
        putIfBlank(openAi, "voice", property(exchange, "agent.runtime.realtime.voice", "alloy"));
        putIfBlank(openAi, "instructions", property(exchange, "agent.runtime.telephony.openai.instructions", "You are a helpful phone assistant."));
        openAi.put("apiKeyConfigured", hasSecret(
            text(openAi, "apiKey"),
            property(exchange, "openai.api.key", ""),
            property(exchange, "spring.ai.openai.api-key", ""),
            System.getenv("OPENAI_API_KEY"),
            System.getProperty("openai.api.key")
        ));
        openAi.put("webhookSecretConfigured", hasSecret(
            text(openAi, "webhookSecret"),
            System.getenv("OPENAI_WEBHOOK_SECRET"),
            System.getProperty("openai.webhook.secret")
        ));

        putIfBlank(twilio, "accountSid", firstNonBlank(
            text(twilio, "accountSid"),
            property(exchange, "camel.component.twilio.account-sid", ""),
            System.getenv("TWILIO_ACCOUNT_SID")
        ));
        putIfBlank(twilio, "fromNumber", firstNonBlank(
            text(twilio, "fromNumber"),
            property(exchange, "agent.runtime.telephony.twilio.from", ""),
            System.getenv("TWILIO_FROM_NUMBER")
        ));
        putIfBlank(twilio, "phoneNumber", firstNonBlank(text(twilio, "phoneNumber"), text(twilio, "fromNumber")));
        putIfBlank(twilio, "trunkName", resolved.path("displayName").asText("agent") + "-sip-trunk");
        twilio.put("authTokenConfigured", hasSecret(
            text(twilio, "authToken"),
            property(exchange, "camel.component.twilio.password", ""),
            System.getenv("TWILIO_AUTH_TOKEN")
        ));
        return resolved;
    }

    private ObjectNode normalizeRequest(Exchange exchange) {
        Object body = exchange.getMessage().getBody();
        if (body == null) {
            return objectMapper.createObjectNode();
        }
        if (body instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }
        if (body instanceof JsonNode jsonNode && jsonNode.isObject()) {
            return (ObjectNode) jsonNode.deepCopy();
        }
        try {
            String payload = exchange.getMessage().getBody(String.class);
            if (payload == null || payload.isBlank()) {
                return objectMapper.createObjectNode();
            }
            JsonNode parsed = objectMapper.readTree(payload);
            return parsed instanceof ObjectNode objectNode ? objectNode : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String property(Exchange exchange, String key, String defaultValue) {
        try {
            String value = exchange.getContext().resolvePropertyPlaceholders("{{" + key + ":" + defaultValue + "}}");
            if (value == null || value.isBlank() || value.contains("{{")) {
                return defaultValue;
            }
            return value.trim();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String defaultPublicBaseUrl(Exchange exchange) {
        String publicBaseUrl = firstNonBlank(
            property(exchange, "agent.runtime.a2a.public-base-url", ""),
            property(exchange, "agent.runtime.telephony.onboarding.public-base-url", "")
        );
        if (!publicBaseUrl.isBlank()) {
            return publicBaseUrl;
        }
        String port = property(exchange, "agui.rpc.port", "8080");
        return "http://localhost:" + port;
    }

    private static boolean hasSecret(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String text(ObjectNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private static void putIfBlank(ObjectNode node, String fieldName, String value) {
        if (text(node, fieldName).isBlank() && value != null && !value.isBlank()) {
            node.put(fieldName, value.trim());
        }
    }

    private static ObjectNode ensureObject(ObjectNode parent, String fieldName) {
        JsonNode existing = parent.path(fieldName);
        if (existing instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode created = parent.objectNode();
        parent.set(fieldName, created);
        return created;
    }

    private static ObjectNode object(ObjectNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value instanceof ObjectNode objectNode ? objectNode : node.objectNode();
    }
}