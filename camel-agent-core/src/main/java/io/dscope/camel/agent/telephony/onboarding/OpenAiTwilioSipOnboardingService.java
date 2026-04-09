package io.dscope.camel.agent.telephony.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;

public final class OpenAiTwilioSipOnboardingService {

    private final ObjectMapper objectMapper;

    public OpenAiTwilioSipOnboardingService(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ObjectNode buildPlan(ObjectNode resolvedRequest) {
        ObjectNode request = resolvedRequest == null ? objectMapper.createObjectNode() : resolvedRequest.deepCopy();
        String tenantId = text(request, "tenantId", "default");
        String agentId = text(request, "agentId", "default");
        String displayName = text(request, "displayName", agentId);
        String publicBaseUrl = trimToEmpty(text(request, "publicBaseUrl", ""));

        ObjectNode openAi = object(request, "openai");
        ObjectNode twilio = object(request, "twilio");

        String projectId = trimToEmpty(text(openAi, "projectId", ""));
        String webhookPath = trimToEmpty(text(openAi, "webhookPath", "/openai/realtime/sip/webhook"));
        String webhookUrl = buildWebhookUrl(publicBaseUrl, webhookPath);
        String sipUri = buildSipUri(projectId);
        String model = text(openAi, "model", "gpt-realtime");
        String voice = text(openAi, "voice", "alloy");
        String instructions = text(openAi, "instructions", "You are a helpful phone assistant.");

        boolean apiKeyConfigured = bool(openAi, "apiKeyConfigured");
        boolean webhookSecretConfigured = bool(openAi, "webhookSecretConfigured");
        boolean accountSidConfigured = !trimToEmpty(text(twilio, "accountSid", "")).isBlank();
        boolean authTokenConfigured = bool(twilio, "authTokenConfigured");

        String trunkName = text(twilio, "trunkName", displayName + "-sip-trunk");
        String fromNumber = trimToEmpty(text(twilio, "fromNumber", ""));
        String phoneNumber = trimToEmpty(text(twilio, "phoneNumber", fromNumber));

        ArrayNode errors = objectMapper.createArrayNode();
        ArrayNode warnings = objectMapper.createArrayNode();
        validate(projectId, webhookUrl, apiKeyConfigured, webhookSecretConfigured, accountSidConfigured, authTokenConfigured, errors, warnings);

        ObjectNode openAiConfig = objectMapper.createObjectNode();
        openAiConfig.put("projectId", projectId);
        openAiConfig.put("sipUri", sipUri);
        openAiConfig.put("webhookPath", webhookPath);
        openAiConfig.put("webhookUrl", webhookUrl);
        openAiConfig.put("model", model);
        openAiConfig.put("voice", voice);
        openAiConfig.put("instructions", instructions);
        openAiConfig.put("apiKeyConfigured", apiKeyConfigured);
        openAiConfig.put("webhookSecretConfigured", webhookSecretConfigured);
        openAiConfig.put("acceptEndpointTemplate", "https://api.openai.com/v1/realtime/calls/{call_id}/accept");
        openAiConfig.put("realtimeWebsocketTemplate", "wss://api.openai.com/v1/realtime?call_id={call_id}");

        ObjectNode twilioConfig = objectMapper.createObjectNode();
        twilioConfig.put("provider", "twilio");
        twilioConfig.put("accountSidMasked", mask(text(twilio, "accountSid", "")));
        twilioConfig.put("authTokenConfigured", authTokenConfigured);
        twilioConfig.put("fromNumber", fromNumber);
        twilioConfig.put("phoneNumber", phoneNumber);
        twilioConfig.put("trunkName", trunkName);
        twilioConfig.put("originationSipUri", sipUri);
        twilioConfig.put("transport", "tls");

        ArrayNode checklist = objectMapper.createArrayNode();
        checklist.add(step("Create or confirm the OpenAI project webhook for realtime.call.incoming.", webhookUrl));
        checklist.add(step("Configure the Twilio Elastic SIP Trunk origination target to the OpenAI SIP URI.", sipUri));
        checklist.add(step("Attach the PSTN phone number to the Twilio trunk.", phoneNumber.isBlank() ? "Provide a Twilio number or trunk DID." : phoneNumber));
        checklist.add(step("Run this service at the public webhook URL and verify OpenAI signature validation.", webhookUrl));
        checklist.add(step("Use the audit SIP endpoint to inspect the stored onboarding record and future call lifecycle.", "/audit/conversation/sip?conversationId=" + TelephonyOnboardingPersistenceService.conversationId(tenantId, agentId)));

        ObjectNode runtime = objectMapper.createObjectNode();
        ArrayNode requiredEnv = objectMapper.createArrayNode();
        requiredEnv.add("OPENAI_API_KEY");
        requiredEnv.add("OPENAI_WEBHOOK_SECRET");
        requiredEnv.add("OPENAI_PROJECT_ID");
        requiredEnv.add("TWILIO_ACCOUNT_SID");
        requiredEnv.add("TWILIO_AUTH_TOKEN");
        requiredEnv.add("TWILIO_FROM_NUMBER");
        runtime.set("requiredEnv", requiredEnv);

        ObjectNode runtimeProperties = objectMapper.createObjectNode();
        runtimeProperties.put("agent.runtime.telephony.openai.project-id", projectId);
        runtimeProperties.put("agent.runtime.telephony.openai.instructions", instructions);
        runtimeProperties.put("agent.runtime.realtime.model", model);
        runtimeProperties.put("agent.runtime.realtime.voice", voice);
        runtimeProperties.put("agent.runtime.telephony.twilio.from", fromNumber);
        runtime.set("properties", runtimeProperties);

        ObjectNode camel = objectMapper.createObjectNode();
        ArrayNode routes = objectMapper.createArrayNode();
        routes.add("/openai/realtime/sip/webhook");
        routes.add("/telephony/onboarding/openai-twilio");
        routes.add("/audit/conversation/sip");
        camel.set("httpRoutes", routes);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("tenantId", tenantId);
        response.put("agentId", agentId);
        response.put("displayName", displayName);
        response.put("topology", "Twilio Elastic SIP Trunk -> OpenAI SIP endpoint -> OpenAI realtime.call.incoming webhook -> this service accepts call");
        response.put("onboardingId", "sip-onboarding-" + tenantId + "-" + agentId + "-" + Instant.now().toEpochMilli());
        response.put("configConversationId", TelephonyOnboardingPersistenceService.conversationId(tenantId, agentId));
        response.put("valid", errors.isEmpty());
        response.set("errors", errors);
        response.set("warnings", warnings);
        response.set("openai", openAiConfig);
        response.set("twilio", twilioConfig);
        response.set("runtime", runtime);
        response.set("camel", camel);
        response.set("checklist", checklist);

        ObjectNode persistedConfig = objectMapper.createObjectNode();
        persistedConfig.put("tenantId", tenantId);
        persistedConfig.put("agentId", agentId);
        persistedConfig.put("displayName", displayName);
        persistedConfig.put("channel", "sip");
        persistedConfig.put("provider", "twilio");
        persistedConfig.put("conversationKind", "sip-onboarding");
        persistedConfig.put("topology", "twilio-elastic-sip-openai-realtime");
        persistedConfig.put("configConversationId", TelephonyOnboardingPersistenceService.conversationId(tenantId, agentId));
        persistedConfig.set("openai", openAiConfig.deepCopy());
        persistedConfig.set("twilio", twilioConfig.deepCopy());
        persistedConfig.set("runtime", runtime.deepCopy());
        persistedConfig.set("checklist", checklist.deepCopy());
        response.set("persistedConfig", persistedConfig);
        return response;
    }

    public static String buildSipUri(String projectId) {
        String normalized = trimToEmpty(projectId);
        if (normalized.isBlank()) {
            return "";
        }
        return "sip:" + normalized + "@sip.api.openai.com;transport=tls";
    }

    private void validate(String projectId,
                          String webhookUrl,
                          boolean apiKeyConfigured,
                          boolean webhookSecretConfigured,
                          boolean accountSidConfigured,
                          boolean authTokenConfigured,
                          ArrayNode errors,
                          ArrayNode warnings) {
        if (projectId.isBlank()) {
            errors.add("Missing OpenAI projectId");
        }
        if (!projectId.isBlank() && !projectId.startsWith("proj_")) {
            warnings.add("OpenAI projectId usually starts with proj_");
        }
        if (webhookUrl.isBlank()) {
            errors.add("Missing publicBaseUrl or webhookUrl; cannot build the OpenAI webhook callback URL");
        }
        if (!apiKeyConfigured) {
            errors.add("Missing OpenAI API key configuration");
        }
        if (!webhookSecretConfigured) {
            errors.add("Missing OpenAI webhook secret configuration");
        }
        if (!accountSidConfigured) {
            errors.add("Missing Twilio account SID");
        }
        if (!authTokenConfigured) {
            errors.add("Missing Twilio auth token configuration");
        }
    }

    private ObjectNode step(String action, String detail) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("action", action);
        node.put("detail", detail == null ? "" : detail);
        return node;
    }

    private ObjectNode object(ObjectNode parent, String fieldName) {
        JsonNode node = parent.path(fieldName);
        return node instanceof ObjectNode objectNode ? objectNode : objectMapper.createObjectNode();
    }

    private static boolean bool(ObjectNode parent, String fieldName) {
        JsonNode node = parent.path(fieldName);
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String value = node.asText("");
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private static String text(ObjectNode parent, String fieldName, String fallback) {
        JsonNode node = parent.path(fieldName);
        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String value = node.asText("");
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String buildWebhookUrl(String publicBaseUrl, String webhookPath) {
        String normalizedBase = trimToEmpty(publicBaseUrl).replaceAll("/+$", "");
        String normalizedPath = webhookPath == null || webhookPath.isBlank() ? "/openai/realtime/sip/webhook" : webhookPath.trim();
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        if (normalizedBase.isBlank()) {
            return "";
        }
        return normalizedBase + normalizedPath;
    }

    private static String mask(String value) {
        String normalized = trimToEmpty(value);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.length() <= 8) {
            return "****";
        }
        return normalized.substring(0, 4) + "..." + normalized.substring(normalized.length() - 4);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}