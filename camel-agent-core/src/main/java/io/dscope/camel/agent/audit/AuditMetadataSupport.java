package io.dscope.camel.agent.audit;

import io.dscope.camel.agent.model.AgentEvent;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AuditMetadataSupport {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^\\s*-\\s*name:\\s*([^\\s#]+).*$");
    private static final Pattern TOPICS_PATTERN = Pattern.compile("^\\s*topics?\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

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
            return new BlueprintMetadata("Agent", "", List.of());
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        String title = "Agent";
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

        return new BlueprintMetadata(title, description, topics.stream().limit(16).toList());
    }

    static Map<String, Object> buildConversationMetadata(String conversationId, List<AgentEvent> events, BlueprintMetadata blueprintMetadata) {
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

        return Map.of(
            "conversationId", conversationId,
            "title", title,
            "description", blueprintMetadata.description(),
            "topics", blueprintMetadata.topics(),
            "agentTitle", blueprintMetadata.agentTitle(),
            "eventCount", events.size(),
            "firstEventAt", firstEventAt == null ? "" : firstEventAt.toString(),
            "lastEventAt", lastEventAt == null ? "" : lastEventAt.toString()
        );
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

    record BlueprintMetadata(String agentTitle, String description, List<String> topics) {
    }
}
