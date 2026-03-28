package io.dscope.camel.agent.springai;

import io.dscope.camel.agent.model.ModelUsage;
import io.dscope.camel.agent.model.TokenUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

final class ModelCostEstimator {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

    private final Properties properties;

    ModelCostEstimator(Properties properties) {
        this.properties = properties == null ? new Properties() : properties;
    }

    ModelUsage estimate(String provider, String model, String apiMode, TokenUsage tokenUsage) {
        TokenUsage normalizedUsage = tokenUsage == null
            ? TokenUsage.of(null, null, null)
            : TokenUsage.of(tokenUsage.promptTokens(), tokenUsage.completionTokens(), tokenUsage.totalTokens());
        if (!normalizedUsage.isReported()) {
            return null;
        }
        BigDecimal promptRate = lookupRate(provider, model, "prompt", "input");
        BigDecimal completionRate = lookupRate(provider, model, "completion", "output");
        BigDecimal promptCost = estimateCost(normalizedUsage.promptTokens(), promptRate);
        BigDecimal completionCost = estimateCost(normalizedUsage.completionTokens(), completionRate);
        BigDecimal totalCost = null;
        if (promptCost != null || completionCost != null) {
            totalCost = defaultAmount(promptCost).add(defaultAmount(completionCost)).setScale(8, RoundingMode.HALF_UP);
        }
        return ModelUsage.of(provider, model, apiMode, normalizedUsage, promptCost, completionCost, totalCost);
    }

    private BigDecimal lookupRate(String provider, String model, String... dimensions) {
        for (String dimension : dimensions) {
            for (String key : candidateKeys(provider, model, dimension)) {
                BigDecimal parsed = parseDecimal(properties.getProperty(key));
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private List<String> candidateKeys(String provider, String model, String dimension) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String normalizedProvider = normalize(provider);
        List<String> modelKeys = modelKeys(model);
        for (String modelKey : modelKeys) {
            if (!normalizedProvider.isBlank() && !modelKey.isBlank()) {
                keys.add("agent.runtime.model-cost." + normalizedProvider + "." + modelKey + "." + dimension + "-usd-per-1m");
            }
        }
        if (!normalizedProvider.isBlank()) {
            keys.add("agent.runtime.model-cost." + normalizedProvider + "." + dimension + "-usd-per-1m");
        }
        keys.add("agent.runtime.model-cost." + dimension + "-usd-per-1m");
        return new ArrayList<>(keys);
    }

    private List<String> modelKeys(String model) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String raw = model == null ? "" : model.trim();
        if (!raw.isBlank()) {
            keys.add(raw);
        }
        String normalized = normalize(raw);
        if (!normalized.isBlank()) {
            keys.add(normalized);
        }
        return new ArrayList<>(keys);
    }

    private BigDecimal estimateCost(Integer tokens, BigDecimal ratePerMillion) {
        if (tokens == null || tokens <= 0 || ratePerMillion == null) {
            return null;
        }
        return ratePerMillion.multiply(BigDecimal.valueOf(tokens))
            .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private BigDecimal defaultAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}