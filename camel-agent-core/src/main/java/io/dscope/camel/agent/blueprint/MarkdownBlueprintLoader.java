package io.dscope.camel.agent.blueprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.dscope.camel.agent.api.BlueprintLoader;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MarkdownBlueprintLoader implements BlueprintLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Override
    public AgentBlueprint load(String location) {
        String markdown = readResource(location);
        String name = readHeading(markdown, "# Agent:");
        String version = readHeading(markdown, "Version:");
        String systemInstruction = readSystemInstruction(markdown);
        String toolsYaml = firstFencedYaml(markdown);

        List<ToolSpec> tools = parseTools(toolsYaml);
        if (tools.isEmpty()) {
            throw new IllegalArgumentException("No tools declared in blueprint: " + location);
        }
        return new AgentBlueprint(
            name == null ? "agent" : name,
            version == null ? "0.0.1" : version,
            systemInstruction == null ? "You are a helpful agent." : systemInstruction,
            tools
        );
    }

    private List<ToolSpec> parseTools(String yaml) {
        List<ToolSpec> tools = new ArrayList<>();
        if (yaml == null || yaml.isBlank()) {
            return tools;
        }
        try {
            JsonNode root = yamlMapper.readTree(yaml);
            JsonNode toolsNode = root.path("tools");
            if (!toolsNode.isArray()) {
                return tools;
            }
            for (JsonNode node : toolsNode) {
                String name = required(node, "name");
                String routeId = text(node, "routeId");
                String endpointUri = text(node, "endpointUri");
                JsonNode inputSchema = node.path("inputSchemaInline").isMissingNode() ? null : node.path("inputSchemaInline");
                JsonNode outputSchema = node.path("outputSchemaInline").isMissingNode() ? null : node.path("outputSchemaInline");
                JsonNode policyNode = node.path("policy");
                ToolPolicy policy = new ToolPolicy(
                    policyNode.path("pii").path("redact").asBoolean(false),
                    policyNode.path("rateLimit").path("perMinute").asInt(0),
                    policyNode.path("timeoutMs").asLong(30_000L)
                );
                tools.add(new ToolSpec(
                    name,
                    text(node, "description"),
                    routeId,
                    endpointUri,
                    inputSchema,
                    outputSchema,
                    policy
                ));
            }
            return tools;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse tools YAML block", e);
        }
    }

    private String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String readHeading(String markdown, String prefix) {
        for (String line : markdown.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private String readSystemInstruction(String markdown) {
        String[] lines = markdown.split("\\R");
        int idx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equalsIgnoreCase("## System")) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || idx + 1 >= lines.length) {
            return null;
        }
        String next = lines[idx + 1].trim();
        return next.isEmpty() ? null : next;
    }

    private String firstFencedYaml(String markdown) {
        int start = markdown.indexOf("```yaml");
        if (start < 0) {
            return null;
        }
        int contentStart = start + "```yaml".length();
        int end = markdown.indexOf("```", contentStart);
        if (end < 0) {
            return null;
        }
        return markdown.substring(contentStart, end).trim();
    }

    private String readResource(String location) {
        try {
            if (location.startsWith("classpath:")) {
                String path = location.substring("classpath:".length());
                try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
                    if (is == null) {
                        throw new IllegalArgumentException("Blueprint not found on classpath: " + location);
                    }
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            String normalized = location.startsWith("file:") ? location.substring("file:".length()) : location;
            return Files.readString(Path.of(normalized), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read blueprint: " + location, e);
        }
    }
}
