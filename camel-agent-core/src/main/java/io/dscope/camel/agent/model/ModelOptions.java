package io.dscope.camel.agent.model;

import java.util.Map;

public record ModelOptions(
    String provider,
    String model,
    Double temperature,
    Integer maxTokens,
    boolean streaming,
    Map<String, String> properties
) {

    public ModelOptions {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public ModelOptions(String model, Double temperature, Integer maxTokens, boolean streaming) {
        this(null, model, temperature, maxTokens, streaming, Map.of());
    }

    public static ModelOptions defaults() {
        return new ModelOptions(null, null, 0.2d, 1024, false, Map.of());
    }

    public ModelOptions withStreaming(boolean enabled) {
        return new ModelOptions(provider, model, temperature, maxTokens, enabled, properties);
    }
}
