package io.dscope.camel.agent.blueprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.dscope.camel.agent.api.BlueprintLoader;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.JsonRouteTemplateSpec;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownBlueprintLoader implements BlueprintLoader {

    private static final Pattern FENCED_YAML_PATTERN = Pattern.compile("```yaml\\s*(.*?)```", Pattern.DOTALL);

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Override
    public AgentBlueprint load(String location) {
        String markdown = readResource(location);
        String name = readHeading(markdown, "# Agent:");
        String version = readHeading(markdown, "Version:");
        String systemInstruction = readSystemInstruction(markdown);

        JsonNode config = mergedYamlConfig(markdown);
        List<ToolSpec> tools = parseTools(config);
        List<JsonRouteTemplateSpec> jsonRouteTemplates = parseJsonRouteTemplates(config);
        tools.addAll(toolsFromTemplates(jsonRouteTemplates));

        if (tools.isEmpty()) {
            throw new IllegalArgumentException("No tools declared in blueprint: " + location);
        }
        return new AgentBlueprint(
            name == null ? "agent" : name,
            version == null ? "0.0.1" : version,
            systemInstruction == null ? "You are a helpful agent." : systemInstruction,
            tools,
            jsonRouteTemplates
        );
    }

    private List<ToolSpec> parseTools(JsonNode root) {
        List<ToolSpec> tools = new ArrayList<>();
        if (root == null || root.isMissingNode()) {
            return tools;
        }
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
    }

    private List<JsonRouteTemplateSpec> parseJsonRouteTemplates(JsonNode root) {
        List<JsonRouteTemplateSpec> templates = new ArrayList<>();
        if (root == null || root.isMissingNode()) {
            return templates;
        }
        JsonNode templateNodes = root.path("jsonRouteTemplates");
        if (!templateNodes.isArray()) {
            return templates;
        }
        for (JsonNode node : templateNodes) {
            String id = required(node, "id");
            String toolName = text(node, "toolName");
            if (toolName == null || toolName.isBlank()) {
                toolName = "route.template." + id;
            }
            JsonNode parametersSchema = node.path("parametersSchema").isMissingNode() ? null : node.path("parametersSchema");
            JsonNode routeTemplate = node.path("routeTemplate");
            if (routeTemplate.isMissingNode() || routeTemplate.isNull()) {
                throw new IllegalArgumentException("jsonRouteTemplates entry missing routeTemplate: " + id);
            }
            templates.add(new JsonRouteTemplateSpec(
                id,
                toolName,
                text(node, "description"),
                text(node, "invokeUriParam"),
                parametersSchema,
                routeTemplate
            ));
        }
        return templates;
    }

    private List<ToolSpec> toolsFromTemplates(List<JsonRouteTemplateSpec> templates) {
        List<ToolSpec> tools = new ArrayList<>();
        for (JsonRouteTemplateSpec template : templates) {
            tools.add(new ToolSpec(
                template.toolName(),
                template.description() == null ? "Instantiate and execute JSON route template: " + template.id() : template.description(),
                null,
                null,
                template.parametersSchema(),
                null,
                new ToolPolicy(false, 0, 30_000L)
            ));
        }
        return tools;
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

    private JsonNode mergedYamlConfig(String markdown) {
        Map<String, JsonNode> merged = new LinkedHashMap<>();
        Matcher matcher = FENCED_YAML_PATTERN.matcher(markdown);
        while (matcher.find()) {
            String yaml = matcher.group(1);
            if (yaml == null || yaml.isBlank()) {
                continue;
            }
            try {
                JsonNode node = yamlMapper.readTree(yaml);
                if (node == null || !node.isObject()) {
                    continue;
                }
                node.fields().forEachRemaining(entry -> merged.put(entry.getKey(), entry.getValue()));
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to parse YAML block in blueprint", e);
            }
        }
        return yamlMapper.valueToTree(merged);
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
