package io.dscope.camel.agent.blueprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.dscope.camel.agent.api.BlueprintLoader;
import io.dscope.camel.agent.model.AgUiPreRunSpec;
import io.dscope.camel.agent.model.A2UiSpec;
import io.dscope.camel.agent.model.A2UiSurfaceSpec;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.BlueprintResourceSpec;
import io.dscope.camel.agent.model.JsonRouteTemplateSpec;
import io.dscope.camel.agent.model.RealtimeSpec;
import io.dscope.camel.agent.model.ResolvedBlueprintResource;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
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
    private final BlueprintResourceResolver resourceResolver = new BlueprintResourceResolver();

    @Override
    public AgentBlueprint load(String location) {
        String markdown = readResource(location);
        String name = readHeading(markdown, "# Agent:");
        String version = readHeading(markdown, "Version:");
        String systemInstruction = readSystemInstruction(markdown);

        JsonNode config = mergedYamlConfig(markdown);
        List<ToolSpec> tools = parseTools(config);
        List<JsonRouteTemplateSpec> jsonRouteTemplates = parseJsonRouteTemplates(config);
        RealtimeSpec realtime = parseRealtime(config);
        AgUiPreRunSpec agUiPreRun = parseAgUiPreRun(config);
        A2UiSpec a2ui = parseA2Ui(config);
        List<BlueprintResourceSpec> resourceSpecs = parseResources(config);
        List<ResolvedBlueprintResource> resources = resourceResolver.resolve(resourceSpecs);
        tools.addAll(toolsFromTemplates(jsonRouteTemplates));

        if (tools.isEmpty()) {
            throw new IllegalArgumentException("No tools declared in blueprint: " + location);
        }
        return new AgentBlueprint(
            name == null ? "agent" : name,
            version == null ? "0.0.1" : version,
            systemInstruction == null ? "You are a helpful agent." : systemInstruction,
            tools,
            jsonRouteTemplates,
            realtime,
            agUiPreRun,
            a2ui,
            resources
        );
    }

    private A2UiSpec parseA2Ui(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return null;
        }
        JsonNode node = root.path("a2ui");
        if (!node.isObject()) {
            return null;
        }
        JsonNode surfacesNode = node.path("surfaces");
        if (!surfacesNode.isArray()) {
            return null;
        }
        List<A2UiSurfaceSpec> surfaces = new ArrayList<>();
        for (JsonNode surfaceNode : surfacesNode) {
            if (!surfaceNode.isObject()) {
                continue;
            }
            String surfaceResource = text(surfaceNode, "surfaceResource", "surface-resource", "templateResource", "template-resource");
            if (surfaceResource == null || surfaceResource.isBlank()) {
                continue;
            }
            Map<String, String> localeResources = new LinkedHashMap<>();
            JsonNode localeNode = surfaceNode.path("localeResources");
            if (!localeNode.isObject()) {
                localeNode = surfaceNode.path("locales");
            }
            if (localeNode.isObject()) {
                localeNode.properties().forEach(entry -> {
                    if (entry.getValue() != null && !entry.getValue().isNull()) {
                        String value = entry.getValue().asText("").trim();
                        if (!value.isBlank()) {
                            localeResources.put(entry.getKey(), value);
                        }
                    }
                });
            }
            surfaces.add(new A2UiSurfaceSpec(
                text(surfaceNode, "name"),
                text(surfaceNode, "widgetTemplate", "widget-template", "template"),
                text(surfaceNode, "surfaceIdTemplate", "surface-id-template"),
                text(surfaceNode, "catalogId", "catalog-id"),
                text(surfaceNode, "catalogResource", "catalog-resource"),
                surfaceResource,
                strings(surfaceNode, "matchFields", "match-fields", "requiredFields", "required-fields"),
                localeResources
            ));
        }
        return surfaces.isEmpty() ? null : new A2UiSpec(surfaces);
    }

    private List<BlueprintResourceSpec> parseResources(JsonNode root) {
        List<BlueprintResourceSpec> resources = new ArrayList<>();
        if (root == null || root.isMissingNode()) {
            return resources;
        }
        JsonNode resourcesNode = root.path("resources");
        if (!resourcesNode.isArray()) {
            return resources;
        }
        for (JsonNode node : resourcesNode) {
            String name = required(node, "name");
            String uri = firstRequired(node, name, "uri", "url");
            resources.add(new BlueprintResourceSpec(
                name,
                uri,
                text(node, "kind"),
                text(node, "format", "mimeType", "mime-type"),
                strings(node, "includeIn", "include-in"),
                text(node, "loadPolicy", "load-policy"),
                text(node, "refreshPolicy", "refresh-policy"),
                Boolean.TRUE.equals(bool(node, "optional")),
                defaultLong(longValue(node, "maxBytes", "max-bytes"), 262_144L)
            ));
        }
        return resources;
    }

    private AgUiPreRunSpec parseAgUiPreRun(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return null;
        }
        JsonNode node = root.path("aguiPreRun");
        if (!node.isObject()) {
            node = root.path("agui").path("preRun");
        }
        if (!node.isObject()) {
            return null;
        }

        JsonNode fallback = node.path("fallback");
        String agentEndpointUri = text(node, "agentEndpointUri", "agent-endpoint-uri");
        Boolean fallbackEnabled = bool(node, "fallbackEnabled", "fallback-enabled");
        String kbToolName = text(fallback, "kbToolName", "kb-tool-name");
        String ticketToolName = text(fallback, "ticketToolName", "ticket-tool-name");
        String kbUri = text(fallback, "kbUri", "kb-uri");
        String ticketUri = text(fallback, "ticketUri", "ticket-uri");
        List<String> ticketKeywords = strings(fallback, "ticketKeywords", "ticket-keywords");
        List<String> fallbackErrorMarkers = strings(fallback, "errorMarkers", "error-markers");

        if (allBlank(agentEndpointUri, kbToolName, ticketToolName, kbUri, ticketUri)
            && fallbackEnabled == null
            && ticketKeywords.isEmpty()
            && fallbackErrorMarkers.isEmpty()) {
            return null;
        }

        return new AgUiPreRunSpec(
            agentEndpointUri,
            fallbackEnabled,
            kbToolName,
            ticketToolName,
            kbUri,
            ticketUri,
            ticketKeywords,
            fallbackErrorMarkers
        );
    }

    private RealtimeSpec parseRealtime(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return null;
        }
        JsonNode node = root.path("realtime");
        if (!node.isObject()) {
            return null;
        }
        JsonNode reconnect = node.path("reconnect");
        return new RealtimeSpec(
            text(node, "provider") == null ? "openai" : text(node, "provider"),
            text(node, "model"),
            text(node, "voice"),
            text(node, "transport") == null ? "server-relay" : text(node, "transport"),
            text(node, "endpointUri"),
            text(node, "inputAudioFormat"),
            text(node, "outputAudioFormat"),
            text(node, "retentionPolicy") == null ? "metadata_transcript" : text(node, "retentionPolicy"),
            integer(reconnect, "maxSendRetries", "max-send-retries"),
            integer(reconnect, "maxReconnects", "max-reconnects"),
            longValue(reconnect, "initialBackoffMs", "initial-backoff-ms"),
            longValue(reconnect, "maxBackoffMs", "max-backoff-ms")
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
            String endpointUri = resolveEndpointUri(node, name);
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

    private String resolveEndpointUri(JsonNode node, String toolName) {
        String endpointUri = text(node, "endpointUri", "endpoint-uri");
        if (endpointUri != null && !endpointUri.isBlank()) {
            return endpointUri;
        }

        String kameletUri = text(node, "kameletUri", "kamelet-uri");
        if (kameletUri != null && !kameletUri.isBlank()) {
            return normalizeKameletUri(kameletUri);
        }

        JsonNode kameletNode = node.path("kamelet");
        if (kameletNode.isMissingNode() || kameletNode.isNull()) {
            return null;
        }
        return buildKameletUri(kameletNode, toolName);
    }

    private String buildKameletUri(JsonNode node, String toolName) {
        if (node.isTextual()) {
            return normalizeKameletUri(node.asText());
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Invalid kamelet configuration for tool '" + toolName + "': expected string or object");
        }

        String templateId = text(node, "templateId", "template-id", "name", "id");
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("Invalid kamelet configuration for tool '" + toolName + "': missing templateId/name/id");
        }
        String action = text(node, "action");
        if (action == null || action.isBlank()) {
            action = "sink";
        }

        StringBuilder uri = new StringBuilder("kamelet:")
            .append(templateId)
            .append("/")
            .append(action);

        JsonNode parameters = firstObject(node, "parameters", "params", "properties");
        if (parameters != null) {
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : parameters.properties()) {
                JsonNode value = entry.getValue();
                if (value == null || value.isNull()) {
                    continue;
                }
                String stringValue = value.isValueNode() ? value.asText() : value.toString();
                if (stringValue == null || stringValue.isBlank()) {
                    continue;
                }
                uri.append(first ? "?" : "&");
                first = false;
                uri.append(urlEncode(entry.getKey())).append("=").append(urlEncode(stringValue));
            }
        }
        return uri.toString();
    }

    private JsonNode firstObject(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isObject()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeKameletUri(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return trimmed.startsWith("kamelet:") ? trimmed : "kamelet:" + trimmed;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

    private String firstRequired(JsonNode node, String name, String... fields) {
        String value = text(node, fields);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field for resource '" + name + "': uri");
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String text(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Boolean bool(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                if (value.isBoolean()) {
                    return value.booleanValue();
                }
                String text = value.asText(null);
                if (text != null && !text.isBlank()) {
                    return Boolean.parseBoolean(text.trim());
                }
            }
        }
        return null;
    }

    private List<String> strings(JsonNode node, String... fields) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isArray()) {
                for (JsonNode item : value) {
                    if (item != null && !item.isNull()) {
                        String text = item.asText("").trim();
                        if (!text.isBlank()) {
                            values.add(text);
                        }
                    }
                }
                if (!values.isEmpty()) {
                    return values;
                }
            } else if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    for (String part : text.split(",")) {
                        if (part != null && !part.isBlank()) {
                            values.add(part.trim());
                        }
                    }
                    if (!values.isEmpty()) {
                        return values;
                    }
                }
            }
        }
        return values;
    }

    private boolean allBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private Integer integer(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                if (value.isInt() || value.isLong()) {
                    return value.intValue();
                }
                String text = value.asText(null);
                if (text != null && !text.isBlank()) {
                    try {
                        return Integer.parseInt(text.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private Long longValue(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                if (value.isLong() || value.isInt()) {
                    return value.longValue();
                }
                String text = value.asText(null);
                if (text != null && !text.isBlank()) {
                    try {
                        return Long.parseLong(text.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private Long defaultLong(Long value, long fallback) {
        return value == null ? fallback : value;
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

        int start = idx + 1;
        while (start < lines.length && lines[start].trim().isEmpty()) {
            start++;
        }
        if (start >= lines.length) {
            return null;
        }

        StringBuilder out = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            String current = lines[i];
            String trimmed = current.trim();
            if (trimmed.startsWith("## ")) {
                break;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(trimmed);
        }

        String instruction = out.toString().trim();
        return instruction.isEmpty() ? null : instruction;
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
                node.properties().forEach(entry -> merged.put(entry.getKey(), entry.getValue()));
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
                try (InputStream is = openClasspathResource(path)) {
                    if (is == null) {
                        throw new IllegalArgumentException("Blueprint not found on classpath: " + location);
                    }
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            if (location.startsWith("http://") || location.startsWith("https://")) {
                try (InputStream is = URI.create(location).toURL().openStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            String normalized = location.startsWith("file:") ? location.substring("file:".length()) : location;
            return Files.readString(Path.of(normalized), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read blueprint: " + location, e);
        }
    }

    private InputStream openClasspathResource(String path) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            InputStream input = contextLoader.getResourceAsStream(path);
            if (input != null) {
                return input;
            }
        }
        ClassLoader loader = MarkdownBlueprintLoader.class.getClassLoader();
        if (loader != null) {
            InputStream input = loader.getResourceAsStream(path);
            if (input != null) {
                return input;
            }
        }
        return MarkdownBlueprintLoader.class.getResourceAsStream(path.startsWith("/") ? path : "/" + path);
    }
}
