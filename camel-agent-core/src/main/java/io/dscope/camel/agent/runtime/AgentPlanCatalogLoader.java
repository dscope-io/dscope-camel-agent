package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.net.URL;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentPlanCatalogLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public AgentPlanCatalog load(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("agents-config location is required");
        }
        try (InputStream stream = open(location.trim())) {
            if (stream == null) {
                throw new IllegalArgumentException("agents-config not found: " + location);
            }
            Map<String, Object> root = YAML_MAPPER.readValue(stream, new TypeReference<Map<String, Object>>() { });
            return toCatalog(root);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load agents-config: " + location, e);
        }
    }

    private AgentPlanCatalog toCatalog(Map<String, Object> root) {
        Object rawPlans = root == null ? null : root.get("plans");
        if (!(rawPlans instanceof List<?> planList)) {
            throw new IllegalArgumentException("agents.yaml must contain a top-level plans list");
        }
        List<AgentPlanSpec> plans = new ArrayList<>();
        for (Object rawPlan : planList) {
            if (!(rawPlan instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Each plan entry must be a map");
            }
            plans.add(toPlan(cast(map)));
        }
        return new AgentPlanCatalog(plans);
    }

    private AgentPlanSpec toPlan(Map<String, Object> raw) {
        Object rawVersions = raw.get("versions");
        if (!(rawVersions instanceof List<?> versionList)) {
            throw new IllegalArgumentException("Plan " + raw.get("name") + " must define versions");
        }
        List<AgentPlanVersionSpec> versions = new ArrayList<>();
        for (Object rawVersion : versionList) {
            if (!(rawVersion instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Plan version entries must be maps");
            }
            Map<String, Object> version = cast(map);
            versions.add(new AgentPlanVersionSpec(
                text(version.get("version")),
                bool(version.get("default")),
                text(version.get("blueprint")),
                aiConfig(version.get("ai"))
            ));
        }
        return new AgentPlanSpec(
            text(raw.get("name")),
            bool(raw.get("default")),
            aiConfig(raw.get("ai")),
            versions
        );
    }

    private AgentAiConfig aiConfig(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return AgentAiConfig.empty();
        }
        Map<String, Object> values = cast(map);
        return new AgentAiConfig(
            text(values.get("provider")),
            text(values.get("model")),
            doubleValue(firstNonNull(values.get("temperature"), values.get("temp"))),
            intValue(firstNonNull(values.get("maxTokens"), values.get("max-tokens"))),
            flattenProperties(values.get("properties"))
        );
    }

    private Map<String, String> flattenProperties(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> flattened = new LinkedHashMap<>();
        flattenInto(flattened, "", cast(map));
        return flattened;
    }

    private void flattenInto(Map<String, String> target, String prefix, Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = text(entry.getKey());
            if (key == null || key.isBlank()) {
                continue;
            }
            String qualified = prefix.isBlank() ? key : prefix + "." + key;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flattenInto(target, qualified, cast(nested));
            } else if (value != null) {
                target.put(qualified, String.valueOf(value).trim());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private InputStream open(String location) throws Exception {
        if (location.startsWith("classpath:")) {
            return getClass().getClassLoader().getResourceAsStream(location.substring("classpath:".length()));
        }
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return URI.create(location).toURL().openStream();
        }
        if (location.startsWith("file:")) {
            return Files.newInputStream(Path.of(URI.create(location)));
        }
        return Files.newInputStream(Path.of(location));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private boolean bool(Object value) {
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Object firstNonNull(Object primary, Object fallback) {
        return primary != null ? primary : fallback;
    }
}
