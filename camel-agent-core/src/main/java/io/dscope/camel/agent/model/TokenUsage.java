package io.dscope.camel.agent.model;

public record TokenUsage(
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens
) {

    public static TokenUsage of(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        Integer normalizedPromptTokens = normalize(promptTokens);
        Integer normalizedCompletionTokens = normalize(completionTokens);
        Integer normalizedTotalTokens = normalize(totalTokens);
        if (normalizedTotalTokens == null
            && (normalizedPromptTokens != null || normalizedCompletionTokens != null)) {
            normalizedTotalTokens = (normalizedPromptTokens == null ? 0 : normalizedPromptTokens)
                + (normalizedCompletionTokens == null ? 0 : normalizedCompletionTokens);
        }
        return new TokenUsage(normalizedPromptTokens, normalizedCompletionTokens, normalizedTotalTokens);
    }

    public boolean isReported() {
        return isPositive(promptTokens) || isPositive(completionTokens) || isPositive(totalTokens);
    }

    private static Integer normalize(Integer value) {
        return value == null || value < 0 ? null : value;
    }

    private static boolean isPositive(Integer value) {
        return value != null && value > 0;
    }
}