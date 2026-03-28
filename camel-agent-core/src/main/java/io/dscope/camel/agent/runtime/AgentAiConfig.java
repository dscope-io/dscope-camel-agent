package io.dscope.camel.agent.runtime;

import io.dscope.camel.agent.model.ModelOptions;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentAiConfig(
    String provider,
    String model,
    Double temperature,
    Integer maxTokens,
    Map<String, String> properties
) {

    public AgentAiConfig {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static AgentAiConfig empty() {
        return new AgentAiConfig("", "", null, null, Map.of());
    }

    public boolean isEmpty() {
        return blank(provider) && blank(model) && temperature == null && maxTokens == null && properties.isEmpty();
    }

    public AgentAiConfig merge(AgentAiConfig override) {
        if (override == null || override.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return override;
        }
        Map<String, String> mergedProperties = new LinkedHashMap<>(properties);
        mergedProperties.putAll(override.properties());
        return new AgentAiConfig(
            firstNonBlank(override.provider(), provider),
            firstNonBlank(override.model(), model),
            override.temperature() == null ? temperature : override.temperature(),
            override.maxTokens() == null ? maxTokens : override.maxTokens(),
            mergedProperties
        );
    }

    public ModelOptions toModelOptions(boolean streaming, ModelOptions fallback) {
        ModelOptions defaults = fallback == null ? ModelOptions.defaults() : fallback;
        return new ModelOptions(
            firstNonBlank(provider, defaults.provider()),
            firstNonBlank(model, defaults.model()),
            temperature == null ? defaults.temperature() : temperature,
            maxTokens == null ? defaults.maxTokens() : maxTokens,
            streaming,
            mergeProperties(defaults.properties(), properties)
        );
    }

    public Map<String, Object> asMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        if (!blank(provider)) {
            data.put("provider", provider);
        }
        if (!blank(model)) {
            data.put("model", model);
        }
        if (temperature != null) {
            data.put("temperature", temperature);
        }
        if (maxTokens != null) {
            data.put("maxTokens", maxTokens);
        }
        if (!properties.isEmpty()) {
            data.put("properties", properties);
        }
        return data;
    }

    private static Map<String, String> mergeProperties(Map<String, String> base, Map<String, String> override) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (override != null) {
            merged.putAll(override);
        }
        return merged;
    }

    private static String firstNonBlank(String primary, String fallback) {
        return blank(primary) ? (fallback == null ? "" : fallback) : primary;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}