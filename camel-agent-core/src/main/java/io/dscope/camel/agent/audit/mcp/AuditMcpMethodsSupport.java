package io.dscope.camel.agent.audit.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class AuditMcpMethodsSupport {

    private static final String BASE_METHODS_RESOURCE = "mcp/audit-methods.yaml";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    List<Map<String, Object>> loadMethods() throws Exception {
        return loadMethodsFromResource(BASE_METHODS_RESOURCE, true);
    }

    String uiOutputUri(Map<String, Object> tool) {
        if (tool == null || tool.isEmpty()) {
            return null;
        }
        Object annotationsRaw = tool.get("annotations");
        if (!(annotationsRaw instanceof Map<?, ?> annotations)) {
            return null;
        }
        Object uiRaw = annotations.get("ui");
        if (!(uiRaw instanceof Map<?, ?> ui)) {
            return null;
        }
        Object outputUri = ui.get("outputUri");
        if (outputUri == null) {
            return null;
        }
        String uri = String.valueOf(outputUri).trim();
        return uri.isBlank() ? null : uri;
    }

    private List<Map<String, Object>> loadMethodsFromResource(String resourcePath, boolean required) throws Exception {
        try (InputStream inputStream = Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                if (required) {
                    throw new IllegalStateException("Missing MCP methods resource: " + resourcePath);
                }
                return List.of();
            }
            JsonNode root = yamlMapper.readTree(inputStream);
            JsonNode methods = root.path("methods");
            if (!methods.isArray()) {
                if (required) {
                    throw new IllegalStateException("Invalid methods resource: expected 'methods' array in " + resourcePath);
                }
                return List.of();
            }

            List<Map<String, Object>> resolved = new ArrayList<>();
            for (JsonNode method : methods) {
                if (!method.isObject()) {
                    continue;
                }
                Map<String, Object> tool = yamlMapper.convertValue(method, MAP_TYPE);
                if (tool != null && !tool.isEmpty()) {
                    resolved.add(tool);
                }
            }
            return resolved;
        } catch (Exception ex) {
            if (required) {
                throw ex;
            }
            return List.of();
        }
    }

}
