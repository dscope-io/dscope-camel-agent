package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.blueprint.BlueprintInstructionRenderer;
import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.agent.runtime.ResolvedAgentPlan;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class RealtimeBrowserSessionInitProcessor implements Processor {

    private static final String DEFAULT_MODEL = "gpt-realtime";
    private static final int DEFAULT_AGENT_PURPOSE_MAX_CHARS = 240;

    private final ObjectMapper objectMapper;
    private final RealtimeBrowserSessionRegistry sessionRegistry;
    private final MarkdownBlueprintLoader markdownBlueprintLoader;
    private final Map<String, AgentBlueprint> blueprintCache;

    public RealtimeBrowserSessionInitProcessor(RealtimeBrowserSessionRegistry sessionRegistry) {
        this.objectMapper = new ObjectMapper();
        this.sessionRegistry = sessionRegistry;
        this.markdownBlueprintLoader = new MarkdownBlueprintLoader();
        this.blueprintCache = new ConcurrentHashMap<>();
    }

    RealtimeBrowserSessionRegistry sessionRegistry() {
        return sessionRegistry;
    }

    public void clearBlueprintCache() {
        blueprintCache.clear();
    }

    public int refreshAgentProfileForAllSessions(AgentBlueprint blueprint, int purposeMaxChars) {
        return refreshAgentProfileForAllSessions(blueprint, purposeMaxChars, 12_000);
    }

    public int refreshAgentProfileForAllSessions(AgentBlueprint blueprint, int purposeMaxChars, int resourceMaxChars) {
        if (blueprint == null) {
            return 0;
        }
        int updated = 0;
        for (String conversationId : sessionRegistry.conversationIds()) {
            if (conversationId == null || conversationId.isBlank()) {
                continue;
            }
            ObjectNode session = sessionRegistry.getSession(conversationId);
            if (session == null) {
                continue;
            }
            seedBlueprintAgentProfile(session, blueprint, purposeMaxChars, true);
            refreshSessionInstructions(session, blueprint, resourceMaxChars);
            sessionRegistry.putSession(conversationId, session);
            updated++;
        }
        return updated;
    }

    public int refreshAgentProfileForConversation(String conversationId, AgentBlueprint blueprint, int purposeMaxChars) {
        return refreshAgentProfileForConversation(conversationId, blueprint, purposeMaxChars, 12_000);
    }

    public int refreshAgentProfileForConversation(String conversationId, AgentBlueprint blueprint, int purposeMaxChars, int resourceMaxChars) {
        if (conversationId == null || conversationId.isBlank() || blueprint == null) {
            return 0;
        }
        ObjectNode session = sessionRegistry.getSession(conversationId);
        if (session == null) {
            return 0;
        }
        seedBlueprintAgentProfile(session, blueprint, purposeMaxChars, true);
        refreshSessionInstructions(session, blueprint, resourceMaxChars);
        sessionRegistry.putSession(conversationId, session);
        return 1;
    }

    public int refreshAgentProfileForConversation(String conversationId, ResolvedAgentPlan resolvedPlan, int purposeMaxChars) {
        return refreshAgentProfileForConversation(conversationId, resolvedPlan, purposeMaxChars, 12_000);
    }

    public int refreshAgentProfileForConversation(String conversationId, ResolvedAgentPlan resolvedPlan, int purposeMaxChars, int resourceMaxChars) {
        if (conversationId == null || conversationId.isBlank() || resolvedPlan == null) {
            return 0;
        }
        AgentBlueprint blueprint = loadBlueprint(resolvedPlan.blueprint());
        if (blueprint == null) {
            return 0;
        }
        ObjectNode session = sessionRegistry.getSession(conversationId);
        if (session == null) {
            return 0;
        }
        seedBlueprintAgentProfile(session, blueprint, purposeMaxChars, true);
        refreshSessionInstructions(session, blueprint, resourceMaxChars);
        seedPlanMetadata(session, resolvedPlan);
        sessionRegistry.putSession(conversationId, session);
        return 1;
    }

    @Override
    public void process(Exchange exchange) {
        String conversationId = firstNonBlank(
            exchange.getMessage().getHeader("conversationId", String.class),
            exchange.getMessage().getHeader("sessionId", String.class)
        );

        if (conversationId.isBlank()) {
            writeError(exchange, 400, "Missing conversationId path parameter");
            return;
        }

        JsonNode root = parseJson(exchange.getMessage().getBody(String.class));
        ResolvedAgentPlan resolvedPlan = resolvePlan(exchange, conversationId, root);
        ObjectNode requestSession = root.path("session").isObject()
            ? (ObjectNode) root.path("session").deepCopy()
            : objectMapper.createObjectNode();
        ObjectNode existingSession = sessionRegistry.getSession(conversationId);
        ObjectNode session = existingSession != null
            ? existingSession.deepCopy()
            : objectMapper.createObjectNode();

        if (!requestSession.isEmpty()) {
            mergeObject(session, requestSession);
        }

        if (!session.hasNonNull("type")) {
            session.put("type", "realtime");
        }
        if (!session.hasNonNull("model")) {
            session.put("model", firstNonBlank(
                property(exchange, "agent.runtime.realtime.model", ""),
                property(exchange, "agent.realtime.model", ""),
                DEFAULT_MODEL
            ));
        }

        ObjectNode metadata = session.path("metadata").isObject()
            ? (ObjectNode) session.path("metadata")
            : objectMapper.createObjectNode();
        if (!metadata.hasNonNull("conversationId")) {
            metadata.put("conversationId", conversationId);
        }
        if (!session.path("metadata").isObject()) {
            session.set("metadata", metadata);
        }

        seedBlueprintAgentProfile(exchange, session, resolvedPlan);
        seedPlanMetadata(session, resolvedPlan);

        // Add voice-specific rules to instructions
        String language = firstNonBlank(
            property(exchange, "agent.runtime.realtime.language", ""),
            property(exchange, "agent.realtime.language", ""),
            "en"
        );
        if (session.hasNonNull("instructions")) {
            String langName = "sk".equalsIgnoreCase(language) ? "Slovak" : "English";
            String voiceRules = "\n\nVoice rules:\n- Respond in " + langName + " only."
                + "\n- Keep responses concise and conversational — suitable for voice."
                + "\n- Do not output raw JSON. Summarize results in natural speech.";
            session.put("instructions", session.get("instructions").asText() + voiceRules);
        }

        // Set input_audio_transcription if not already configured
        if (!session.hasNonNull("input_audio_transcription")) {
            ObjectNode transcription = objectMapper.createObjectNode();
            transcription.put("model", "gpt-4o-mini-transcribe");
            transcription.put("language", "sk".equalsIgnoreCase(language) ? "sk" : "en");
            session.set("input_audio_transcription", transcription);
        }

        // Set turn_detection if not already configured
        if (!session.hasNonNull("turn_detection")) {
            ObjectNode turnDetection = objectMapper.createObjectNode();
            turnDetection.put("type", "server_vad");
            turnDetection.put("threshold", 0.5);
            turnDetection.put("prefix_padding_ms", 300);
            turnDetection.put("silence_duration_ms", 1200);
            session.set("turn_detection", turnDetection);
        }

        String configuredVoice = firstNonBlank(
            text(session, "voice"),
            text(session.path("audio").path("output"), "voice"),
            property(exchange, "agent.runtime.realtime.voice", ""),
            property(exchange, "agent.realtime.voice", "")
        );
        if (!configuredVoice.isBlank() && !session.hasNonNull("voice")) {
            session.put("voice", configuredVoice);
        }

        sessionRegistry.putSession(conversationId, session);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("conversationId", conversationId);
        response.put("initialized", true);
        response.set("session", session);

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(response.toString());
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private void writeError(Exchange exchange, int statusCode, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("error", message == null ? "unknown error" : message);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode);
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(error.toString());
    }

    private void mergeObject(ObjectNode target, JsonNode source) {
        if (target == null || source == null || !source.isObject()) {
            return;
        }
        source.properties().forEach(entry -> {
            String field = entry.getKey();
            JsonNode incoming = entry.getValue();
            JsonNode existing = target.get(field);
            if (incoming != null && incoming.isObject() && existing != null && existing.isObject()) {
                mergeObject((ObjectNode) existing, incoming);
            } else if (incoming != null) {
                target.set(field, incoming.deepCopy());
            }
        });
    }

    private void seedBlueprintAgentProfile(Exchange exchange, ObjectNode session, ResolvedAgentPlan resolvedPlan) {
        if (session == null) {
            return;
        }
        String blueprintLocation = resolvedPlan == null ? "" : resolvedPlan.blueprint();
        if (blueprintLocation.isBlank()) {
            return;
        }

        AgentBlueprint blueprint = loadBlueprint(blueprintLocation);
        if (blueprint == null) {
            return;
        }

        int purposeMaxChars = intProperty(exchange, DEFAULT_AGENT_PURPOSE_MAX_CHARS,
            "agent.runtime.realtime.agent-profile-purpose-max-chars",
            "agent.runtime.realtime.agentProfilePurposeMaxChars",
            "agent.realtime.agent-profile-purpose-max-chars",
            "agent.realtime.agentProfilePurposeMaxChars");

        seedBlueprintAgentProfile(session, blueprint, purposeMaxChars, false);

        // Set session.instructions from the full blueprint system instruction
        // so the browser can use it in session.update for the Realtime API.
        String systemInstruction = BlueprintInstructionRenderer.renderForRealtime(blueprint,
            realtimeResourceContextMaxChars(exchange, 12_000));
        if (systemInstruction != null && !systemInstruction.isBlank() && !session.hasNonNull("instructions")) {
            session.put("instructions", systemInstruction.trim());
        }
    }

    private void refreshSessionInstructions(ObjectNode session, AgentBlueprint blueprint, int defaultValue) {
        if (session == null || blueprint == null) {
            return;
        }
        String existing = session.path("instructions").asText("");
        String voiceRules = "";
        int voiceRulesIndex = existing.indexOf("\n\nVoice rules:\n");
        if (voiceRulesIndex >= 0) {
            voiceRules = existing.substring(voiceRulesIndex);
        }
        String refreshed = BlueprintInstructionRenderer.renderForRealtime(blueprint, defaultValue);
        if (refreshed != null && !refreshed.isBlank()) {
            session.put("instructions", refreshed.trim() + voiceRules);
        }
    }

    private int realtimeResourceContextMaxChars(Exchange exchange, int defaultValue) {
        return intProperty(exchange, defaultValue,
            "agent.runtime.realtime.resource-context-max-chars",
            "agent.runtime.realtime.resourceContextMaxChars",
            "agent.realtime.resource-context-max-chars",
            "agent.realtime.resourceContextMaxChars");
    }

    private void seedPlanMetadata(ObjectNode session, ResolvedAgentPlan resolvedPlan) {
        if (session == null || resolvedPlan == null || resolvedPlan.legacyMode()) {
            return;
        }
        ObjectNode metadata = ensureObject(session, "metadata");
        ObjectNode camelAgent = ensureObject(metadata, "camelAgent");
        ObjectNode plan = ensureObject(camelAgent, "plan");
        plan.put("name", resolvedPlan.planName());
        plan.put("version", resolvedPlan.planVersion());
        plan.put("blueprint", resolvedPlan.blueprint());
        plan.put("resolvedAt", Instant.now().toString());
    }

    private void seedBlueprintAgentProfile(ObjectNode session, AgentBlueprint blueprint, int purposeMaxChars, boolean forceUpdate) {
        if (session == null || blueprint == null) {
            return;
        }

        ObjectNode metadata = ensureObject(session, "metadata");
        ObjectNode camelAgent = ensureObject(metadata, "camelAgent");
        ObjectNode context = ensureObject(camelAgent, "context");
        ObjectNode agentProfile = ensureObject(camelAgent, "agentProfile");

        putValue(agentProfile, "name", blueprint.name(), "agent", forceUpdate);
        putValue(agentProfile, "version", blueprint.version(), "0.0.1", forceUpdate);
        putValue(agentProfile, "purpose", summarizePurpose(blueprint.systemInstruction(), purposeMaxChars), "You are a helpful agent.", forceUpdate);
        if (!agentProfile.hasNonNull("seededAt")) {
            agentProfile.put("seededAt", Instant.now().toString());
        }
        if (forceUpdate) {
            agentProfile.put("blueprintRefreshedAt", Instant.now().toString());
        }

        ArrayNode tools = objectMapper.createArrayNode();
        if (blueprint.tools() != null) {
            for (ToolSpec tool : blueprint.tools()) {
                if (tool == null || tool.name() == null || tool.name().isBlank()) {
                    continue;
                }
                tools.add(tool.name());
            }
        }
        if (forceUpdate || !agentProfile.path("tools").isArray() || agentProfile.path("tools").isEmpty()) {
            if (!tools.isEmpty()) {
                agentProfile.set("tools", tools);
            } else if (forceUpdate) {
                agentProfile.remove("tools");
            }
        }

        if (forceUpdate || !context.hasNonNull("agentPurpose")) {
            context.put("agentPurpose", firstNonBlank(agentProfile.path("purpose").asText(""), "You are a helpful agent."));
        }
        if (!context.hasNonNull("agentFocusHint")) {
            context.put("agentFocusHint", "Follow the configured agent purpose and tools when continuing this session.");
        }
    }

    private AgentBlueprint loadBlueprint(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            return blueprintCache.computeIfAbsent(location, markdownBlueprintLoader::load);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ObjectNode ensureObject(ObjectNode parent, String fieldName) {
        JsonNode current = parent.get(fieldName);
        if (current instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode replacement = objectMapper.createObjectNode();
        parent.set(fieldName, replacement);
        return replacement;
    }

    private void putValue(ObjectNode node, String key, String value, String fallback, boolean forceUpdate) {
        if (node == null || key == null || key.isBlank()) {
            return;
        }
        if (!forceUpdate && node.hasNonNull(key)) {
            return;
        }
        String resolved = firstNonBlank(value, fallback);
        if (!resolved.isBlank()) {
            node.put(key, resolved);
        }
    }

    private String summarizePurpose(String systemInstruction, int maxChars) {
        String raw = firstNonBlank(systemInstruction, "").trim();
        if (raw.isBlank()) {
            return "";
        }
        String normalized = raw.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (maxChars > 0 && normalized.length() > maxChars) {
            return normalized.substring(0, maxChars).trim();
        }
        return normalized;
    }

    private int intProperty(Exchange exchange, int defaultValue, String... keys) {
        for (String key : keys) {
            String value = property(exchange, key, "");
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

    private ResolvedAgentPlan resolvePlan(Exchange exchange, String conversationId, JsonNode root) {
        AgentPlanSelectionResolver resolver = exchange.getContext().getRegistry().findSingleByType(AgentPlanSelectionResolver.class);
        if (resolver == null) {
            return ResolvedAgentPlan.legacy(property(exchange, "agent.blueprint", ""));
        }
        String requestedPlanName = firstNonBlank(
            exchange.getMessage().getHeader(AgentHeaders.PLAN_NAME, String.class),
            text(root, "planName"),
            text(root.path("session"), "planName")
        );
        String requestedPlanVersion = firstNonBlank(
            exchange.getMessage().getHeader(AgentHeaders.PLAN_VERSION, String.class),
            text(root, "planVersion"),
            text(root.path("session"), "planVersion")
        );
        ResolvedAgentPlan resolved = resolver.resolve(
            conversationId,
            requestedPlanName.isBlank() ? null : requestedPlanName,
            requestedPlanVersion.isBlank() ? null : requestedPlanVersion,
            property(exchange, "agent.agents-config", ""),
            property(exchange, "agent.blueprint", "")
        );
        if (!resolved.legacyMode()) {
            exchange.getMessage().setHeader(AgentHeaders.RESOLVED_PLAN_NAME, resolved.planName());
            exchange.getMessage().setHeader(AgentHeaders.RESOLVED_PLAN_VERSION, resolved.planVersion());
        }
        exchange.getMessage().setHeader(AgentHeaders.RESOLVED_BLUEPRINT, resolved.blueprint());
        return resolved;
    }
}
