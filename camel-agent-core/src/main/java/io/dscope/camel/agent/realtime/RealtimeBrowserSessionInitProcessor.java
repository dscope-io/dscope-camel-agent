package io.dscope.camel.agent.realtime;

import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

        seedBlueprintAgentProfile(exchange, session);

        String configuredVoice = firstNonBlank(
            text(session.path("audio").path("output"), "voice"),
            property(exchange, "agent.runtime.realtime.voice", ""),
            property(exchange, "agent.realtime.voice", "")
        );
        if (!configuredVoice.isBlank()) {
            ObjectNode audio = session.path("audio").isObject()
                ? (ObjectNode) session.path("audio")
                : objectMapper.createObjectNode();
            ObjectNode output = audio.path("output").isObject()
                ? (ObjectNode) audio.path("output")
                : objectMapper.createObjectNode();
            if (!output.hasNonNull("voice")) {
                output.put("voice", configuredVoice);
            }
            if (!audio.path("output").isObject()) {
                audio.set("output", output);
            }
            if (!session.path("audio").isObject()) {
                session.set("audio", audio);
            }
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
        source.fields().forEachRemaining(entry -> {
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

    private void seedBlueprintAgentProfile(Exchange exchange, ObjectNode session) {
        if (session == null) {
            return;
        }
        String blueprintLocation = property(exchange, "agent.blueprint", "");
        if (blueprintLocation.isBlank()) {
            return;
        }

        AgentBlueprint blueprint = loadBlueprint(blueprintLocation);
        if (blueprint == null) {
            return;
        }

        ObjectNode metadata = ensureObject(session, "metadata");
        ObjectNode camelAgent = ensureObject(metadata, "camelAgent");
        ObjectNode context = ensureObject(camelAgent, "context");
        ObjectNode agentProfile = ensureObject(camelAgent, "agentProfile");
        int purposeMaxChars = intProperty(exchange, DEFAULT_AGENT_PURPOSE_MAX_CHARS,
            "agent.runtime.realtime.agent-profile-purpose-max-chars",
            "agent.runtime.realtime.agentProfilePurposeMaxChars",
            "agent.realtime.agent-profile-purpose-max-chars",
            "agent.realtime.agentProfilePurposeMaxChars");

        putIfMissing(agentProfile, "name", blueprint.name(), "agent");
        putIfMissing(agentProfile, "version", blueprint.version(), "0.0.1");
        putIfMissing(agentProfile, "purpose", summarizePurpose(blueprint.systemInstruction(), purposeMaxChars), "You are a helpful agent.");
        if (!agentProfile.hasNonNull("seededAt")) {
            agentProfile.put("seededAt", Instant.now().toString());
        }

        if (!agentProfile.path("tools").isArray() || agentProfile.path("tools").isEmpty()) {
            ArrayNode tools = objectMapper.createArrayNode();
            if (blueprint.tools() != null) {
                for (ToolSpec tool : blueprint.tools()) {
                    if (tool == null || tool.name() == null || tool.name().isBlank()) {
                        continue;
                    }
                    tools.add(tool.name());
                }
            }
            if (!tools.isEmpty()) {
                agentProfile.set("tools", tools);
            }
        }

        if (!context.hasNonNull("agentPurpose")) {
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

    private void putIfMissing(ObjectNode node, String key, String value, String fallback) {
        if (node == null || key == null || key.isBlank() || node.hasNonNull(key)) {
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
}
