package io.dscope.camel.agent.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record ModelUsage(
    String provider,
    String model,
    String apiMode,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    BigDecimal promptCostUsd,
    BigDecimal completionCostUsd,
    BigDecimal totalCostUsd,
    String currency,
    boolean estimatedCost
) {

    public ModelUsage {
        provider = normalizeText(provider);
        model = normalizeText(model);
        apiMode = normalizeText(apiMode);
        promptTokens = normalizeInteger(promptTokens);
        completionTokens = normalizeInteger(completionTokens);
        totalTokens = normalizeInteger(totalTokens);
        promptCostUsd = normalizeAmount(promptCostUsd);
        completionCostUsd = normalizeAmount(completionCostUsd);
        totalCostUsd = normalizeAmount(totalCostUsd);
        currency = normalizeText(currency);
    }

    public static ModelUsage of(String provider,
                                String model,
                                String apiMode,
                                TokenUsage tokenUsage,
                                BigDecimal promptCostUsd,
                                BigDecimal completionCostUsd,
                                BigDecimal totalCostUsd) {
        TokenUsage normalizedUsage = tokenUsage == null
            ? TokenUsage.of(null, null, null)
            : TokenUsage.of(tokenUsage.promptTokens(), tokenUsage.completionTokens(), tokenUsage.totalTokens());
        boolean hasCost = promptCostUsd != null || completionCostUsd != null || totalCostUsd != null;
        return new ModelUsage(
            provider,
            model,
            apiMode,
            normalizedUsage.promptTokens(),
            normalizedUsage.completionTokens(),
            normalizedUsage.totalTokens(),
            promptCostUsd,
            completionCostUsd,
            totalCostUsd,
            hasCost ? "USD" : "",
            hasCost
        );
    }

    public TokenUsage tokenUsage() {
        return TokenUsage.of(promptTokens, completionTokens, totalTokens);
    }

    public boolean hasTokenUsage() {
        return tokenUsage().isReported();
    }

    public boolean hasCostEstimate() {
        return promptCostUsd != null || completionCostUsd != null || totalCostUsd != null;
    }

    public boolean isReported() {
        return hasTokenUsage() || hasCostEstimate();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static Integer normalizeInteger(Integer value) {
        return value == null || value < 0 ? null : value;
    }

    private static BigDecimal normalizeAmount(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.max(BigDecimal.ZERO).setScale(8, RoundingMode.HALF_UP);
    }
}