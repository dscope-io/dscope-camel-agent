package io.dscope.camel.agent.model;

public record ModelOptions(
    String model,
    Double temperature,
    Integer maxTokens,
    boolean streaming
) {
    public static ModelOptions defaults() {
        return new ModelOptions(null, 0.2d, 1024, false);
    }
}
