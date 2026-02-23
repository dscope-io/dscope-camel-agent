package io.dscope.camel.agent.registry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CorrelationRegistry {

    private static final CorrelationRegistry GLOBAL = new CorrelationRegistry();

    private final Map<String, Map<String, String>> correlations = new ConcurrentHashMap<>();

    public static CorrelationRegistry global() {
        return GLOBAL;
    }

    public void bind(String sourceId, String correlationType, String correlatedId) {
        if (sourceId == null || sourceId.isBlank() || correlationType == null || correlationType.isBlank()) {
            return;
        }
        correlations.computeIfAbsent(sourceId, ignored -> new ConcurrentHashMap<>())
            .put(correlationType, correlatedId);
    }

    public String resolve(String sourceId, String correlationType, String fallbackValue) {
        if (sourceId == null || sourceId.isBlank() || correlationType == null || correlationType.isBlank()) {
            return fallbackValue;
        }
        return correlations.getOrDefault(sourceId, Map.of()).getOrDefault(correlationType, fallbackValue);
    }

    public void clear(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return;
        }
        correlations.remove(sourceId);
    }
}