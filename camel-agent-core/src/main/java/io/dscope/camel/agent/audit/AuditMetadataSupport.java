package io.dscope.camel.agent.audit;

import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.runtime.AgentAiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AuditMetadataSupport {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^\\s*-\\s*name:\\s*([^\\s#]+).*$");
    private static final Pattern TOPICS_PATTERN = Pattern.compile("^\\s*topics?\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\s*version\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private AuditMetadataSupport() {
    }

    static String loadBlueprintContent(String blueprintUri) throws Exception {
        if (blueprintUri == null || blueprintUri.isBlank()) {
            return null;
        }
        if (blueprintUri.startsWith("classpath:")) {
            String resourcePath = blueprintUri.substring("classpath:".length());
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    return null;
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (blueprintUri.startsWith("file:")) {
            Path path = Path.of(URI.create(blueprintUri));
            if (!Files.exists(path)) {
                return null;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        Path path = Path.of(blueprintUri);
        if (!Files.exists(path)) {
            return null;
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    static BlueprintMetadata parseBlueprintMetadata(String content) {
        if (content == null || content.isBlank()) {
            return new BlueprintMetadata("Agent", "", "", List.of());
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        String title = "Agent";
        String version = "";
        String description = "";
        boolean inCodeBlock = false;
        boolean sawTopHeading = false;
        LinkedHashSet<String> topics = new LinkedHashSet<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (!sawTopHeading && trimmed.startsWith("# ")) {
                sawTopHeading = true;
                title = normalizedAgentTitle(trimmed.substring(2).trim());
                continue;
            }

            Matcher versionMatcher = VERSION_PATTERN.matcher(trimmed);
            if (versionMatcher.matches()) {
                version = versionMatcher.group(1).trim();
                continue;
            }

            Matcher topicsMatcher = TOPICS_PATTERN.matcher(trimmed);
            if (topicsMatcher.matches()) {
                for (String topic : topicsMatcher.group(1).split(",")) {
                    String candidate = topic.trim();
                    if (!candidate.isBlank()) {
                        topics.add(candidate);
                    }
                }
            }

            if (trimmed.startsWith("## ")) {
                String section = trimmed.substring(3).trim();
                if (!section.isBlank() && !isBoilerplateSection(section)) {
                    topics.add(section);
                }
                continue;
            }

            Matcher toolMatcher = TOOL_NAME_PATTERN.matcher(line);
            if (toolMatcher.matches()) {
                String tool = toolMatcher.group(1).trim();
                if (!tool.isBlank()) {
                    topics.add(tool);
                }
                continue;
            }

            if (!inCodeBlock
                && sawTopHeading
                && description.isBlank()
                && !trimmed.isBlank()
                && !trimmed.startsWith("#")
                && !trimmed.startsWith("-")
                && !trimmed.matches("^\\d+\\..*")
                && !trimmed.toLowerCase(Locale.ROOT).startsWith("version:")) {
                description = trimmed;
            }
        }

        return new BlueprintMetadata(title, version, description, topics.stream().limit(16).toList());
    }

    static BlueprintMetadata loadBlueprintMetadata(String blueprintUri) {
        try {
            return parseBlueprintMetadata(loadBlueprintContent(blueprintUri));
        } catch (Exception ignored) {
            return new BlueprintMetadata("Agent", "", "", List.of());
        }
    }

    static Map<String, Object> buildConversationMetadata(String conversationId, List<AgentEvent> events, BlueprintMetadata blueprintMetadata) {
        return buildConversationMetadata(conversationId, events, blueprintMetadata, AgentStepMetadata.fromBlueprint("", blueprintMetadata));
    }

    static Map<String, Object> buildConversationMetadata(String conversationId,
                                                         List<AgentEvent> events,
                                                         BlueprintMetadata blueprintMetadata,
                                                         AgentStepMetadata agentStepMetadata) {
        String title = blueprintMetadata.agentTitle() + " conversation";
        if (title.length() > 96) {
            title = title.substring(0, 96) + "…";
        }

        Instant firstEventAt = null;
        Instant lastEventAt = null;
        for (AgentEvent event : events) {
            Instant timestamp = event.timestamp();
            if (timestamp == null) {
                continue;
            }
            if (firstEventAt == null || timestamp.isBefore(firstEventAt)) {
                firstEventAt = timestamp;
            }
            if (lastEventAt == null || timestamp.isAfter(lastEventAt)) {
                lastEventAt = timestamp;
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("conversationId", conversationId);
        metadata.put("title", title);
        metadata.put("description", blueprintMetadata.description());
        metadata.put("topics", blueprintMetadata.topics());
        metadata.put("agentTitle", blueprintMetadata.agentTitle());
        metadata.put("agentName", blankToFallback(agentStepMetadata.agentName(), blueprintMetadata.agentTitle()));
        metadata.put("agentVersion", blankToFallback(agentStepMetadata.agentVersion(), blueprintMetadata.agentVersion()));
        metadata.put("eventCount", events.size());
        metadata.put("firstEventAt", firstEventAt == null ? "" : firstEventAt.toString());
        metadata.put("lastEventAt", lastEventAt == null ? "" : lastEventAt.toString());
        A2ACorrelationMetadata a2a = deriveA2ACorrelation(events);
        if (a2a.present()) {
            metadata.put("conversationKind", "a2a-linked");
            metadata.put("a2aAgentId", a2a.agentId());
            metadata.put("a2aRemoteConversationId", a2a.remoteConversationId());
            metadata.put("a2aRemoteTaskId", a2a.remoteTaskId());
            metadata.put("a2aLinkedConversationId", a2a.linkedConversationId());
            metadata.put("a2aParentConversationId", a2a.parentConversationId());
            metadata.put("a2aRootConversationId", a2a.rootConversationId());
        } else {
            metadata.put("conversationKind", "standard");
        }
        if (!agentStepMetadata.planName().isBlank()) {
            metadata.put("planName", agentStepMetadata.planName());
        }
        if (!agentStepMetadata.planVersion().isBlank()) {
            metadata.put("planVersion", agentStepMetadata.planVersion());
        }
        if (!agentStepMetadata.blueprintUri().isBlank()) {
            metadata.put("blueprintUri", agentStepMetadata.blueprintUri());
        }
        if (!agentStepMetadata.ai().isEmpty()) {
            metadata.put("ai", agentStepMetadata.ai().asMap());
        }
        metadata.put("modelUsageTotals", AuditUsageSupport.summarizeTotals(events));
        return metadata;
    }

    static A2ACorrelationMetadata deriveA2ACorrelation(List<AgentEvent> events) {
        if (events == null) {
            return A2ACorrelationMetadata.EMPTY;
        }
        A2ACorrelationMetadata current = A2ACorrelationMetadata.EMPTY;
        for (AgentEvent event : events) {
            JsonNode correlation = extractCorrelation(event == null ? null : event.payload());
            if (correlation == null) {
                continue;
            }
            current = current.merge(correlation);
        }
        return current;
    }

    static AgentStepMetadata deriveAgentStepMetadata(List<AgentEvent> events, String blueprintUri) {
        AgentStepMetadata state = AgentStepMetadata.fromBlueprint(blueprintUri, loadBlueprintMetadata(blueprintUri));
        if (events == null) {
            return state;
        }
        for (AgentEvent event : events) {
            state = advanceAgentStepMetadata(state, event);
        }
        return state;
    }

    static AgentStepMetadata advanceAgentStepMetadata(AgentStepMetadata current, AgentEvent event) {
        AgentStepMetadata state = current == null
            ? AgentStepMetadata.fromBlueprint("", new BlueprintMetadata("Agent", "", "", List.of()))
            : current;
        if (event == null || event.type() == null || event.payload() == null || event.payload().isNull()) {
            return state;
        }

        if ("conversation.plan.selected".equals(event.type())) {
            String planName = text(event.payload(), "planName");
            String planVersion = text(event.payload(), "planVersion");
            String blueprintUri = firstNonBlank(text(event.payload(), "blueprint"), text(event.payload(), "blueprintUri"), state.blueprintUri());
            BlueprintMetadata metadata = loadBlueprintMetadata(blueprintUri);
            return new AgentStepMetadata(
                blankToFallback(planName, state.planName()),
                blankToFallback(planVersion, state.planVersion()),
                blueprintUri,
                blankToFallback(metadata.agentTitle(), state.agentName()),
                blankToFallback(metadata.agentVersion(), state.agentVersion()),
                aiConfig(event.payload().path("ai"))
            );
        }

        if ("agent.definition.refreshed".equals(event.type())) {
            String blueprintUri = firstNonBlank(text(event.payload(), "blueprint"), text(event.payload(), "blueprintUri"), state.blueprintUri());
            BlueprintMetadata metadata = loadBlueprintMetadata(blueprintUri);
            return new AgentStepMetadata(
                state.planName(),
                state.planVersion(),
                blueprintUri,
                firstNonBlank(text(event.payload(), "agentName"), metadata.agentTitle(), state.agentName()),
                firstNonBlank(text(event.payload(), "agentVersion"), metadata.agentVersion(), state.agentVersion()),
                state.ai()
            );
        }

        return state;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static JsonNode extractCorrelation(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return null;
        }
        List<JsonNode> candidates = List.of(
            payload.path("correlation"),
            payload.path("_correlation"),
            payload.path("payload").path("correlation"),
            payload.path("payload").path("_correlation")
        );
        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isObject()) {
                return candidate;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    private static String normalizedAgentTitle(String heading) {
        if (heading == null || heading.isBlank()) {
            return "Agent";
        }
        if (heading.toLowerCase(Locale.ROOT).startsWith("agent:")) {
            return heading.substring("agent:".length()).trim();
        }
        return heading.trim();
    }

    private static boolean isBoilerplateSection(String section) {
        String normalized = section.toLowerCase(Locale.ROOT);
        return normalized.equals("system")
            || normalized.equals("tools")
            || normalized.equals("json route templates")
            || normalized.equals("realtime")
            || normalized.equals("agui pre-run");
    }

    record BlueprintMetadata(String agentTitle, String agentVersion, String description, List<String> topics) {
    }

    record AgentStepMetadata(String planName,
                             String planVersion,
                             String blueprintUri,
                             String agentName,
                             String agentVersion,
                             AgentAiConfig ai) {
        static AgentStepMetadata fromBlueprint(String blueprintUri, BlueprintMetadata metadata) {
            BlueprintMetadata resolved = metadata == null ? new BlueprintMetadata("Agent", "", "", List.of()) : metadata;
            return new AgentStepMetadata(
                "",
                "",
                blueprintUri == null ? "" : blueprintUri,
                resolved.agentTitle(),
                resolved.agentVersion(),
                AgentAiConfig.empty()
            );
        }

        Map<String, Object> asMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            if (!planName.isBlank()) {
                data.put("planName", planName);
            }
            if (!planVersion.isBlank()) {
                data.put("planVersion", planVersion);
            }
            if (!blueprintUri.isBlank()) {
                data.put("blueprintUri", blueprintUri);
            }
            if (!agentName.isBlank()) {
                data.put("agentName", agentName);
            }
            if (!agentVersion.isBlank()) {
                data.put("agentVersion", agentVersion);
            }
            if (ai != null && !ai.isEmpty()) {
                data.put("ai", ai.asMap());
            }
            return data;
        }
    }

    private static AgentAiConfig aiConfig(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return AgentAiConfig.empty();
        }
        Map<String, String> properties = new LinkedHashMap<>();
        JsonNode propertyNode = node.path("properties");
        if (propertyNode.isObject()) {
            propertyNode.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                properties.put(entry.getKey(), value == null || value.isNull() ? "" : value.asText(""));
            });
        }
        Double temperature = node.path("temperature").isNumber() ? node.path("temperature").doubleValue() : null;
        Integer maxTokens = node.path("maxTokens").isNumber() ? node.path("maxTokens").intValue() : null;
        return new AgentAiConfig(
            text(node, "provider"),
            text(node, "model"),
            temperature,
            maxTokens,
            properties
        );
    }

    record A2ACorrelationMetadata(String agentId,
                                  String remoteConversationId,
                                  String remoteTaskId,
                                  String linkedConversationId,
                                  String parentConversationId,
                                  String rootConversationId) {
        private static final A2ACorrelationMetadata EMPTY = new A2ACorrelationMetadata("", "", "", "", "", "");

        boolean present() {
            return !agentId.isBlank()
                || !remoteConversationId.isBlank()
                || !remoteTaskId.isBlank()
                || !linkedConversationId.isBlank()
                || !parentConversationId.isBlank()
                || !rootConversationId.isBlank();
        }

        A2ACorrelationMetadata merge(JsonNode node) {
            return new A2ACorrelationMetadata(
                firstNonBlank(agentId, text(node, "a2aAgentId")),
                firstNonBlank(remoteConversationId, text(node, "a2aRemoteConversationId")),
                firstNonBlank(remoteTaskId, text(node, "a2aRemoteTaskId")),
                firstNonBlank(linkedConversationId, text(node, "a2aLinkedConversationId")),
                firstNonBlank(parentConversationId, text(node, "a2aParentConversationId")),
                firstNonBlank(rootConversationId, text(node, "a2aRootConversationId"))
            );
        }

        Map<String, Object> asMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            if (!agentId.isBlank()) {
                data.put("agentId", agentId);
            }
            if (!remoteConversationId.isBlank()) {
                data.put("remoteConversationId", remoteConversationId);
            }
            if (!remoteTaskId.isBlank()) {
                data.put("remoteTaskId", remoteTaskId);
            }
            if (!linkedConversationId.isBlank()) {
                data.put("linkedConversationId", linkedConversationId);
            }
            if (!parentConversationId.isBlank()) {
                data.put("parentConversationId", parentConversationId);
            }
            if (!rootConversationId.isBlank()) {
                data.put("rootConversationId", rootConversationId);
            }
            return data;
        }
    }
}
