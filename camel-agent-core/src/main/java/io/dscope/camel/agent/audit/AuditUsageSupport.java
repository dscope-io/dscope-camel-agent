package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import io.dscope.camel.agent.model.AgentEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AuditUsageSupport {

    private AuditUsageSupport() {
    }

    public static Map<String, Object> summarize(List<AgentEvent> events) {
        SummaryAccumulator totals = new SummaryAccumulator("", "", "");
        Map<String, SummaryAccumulator> byModel = new LinkedHashMap<>();
        List<Map<String, Object>> calls = new ArrayList<>();
        if (events != null) {
            for (AgentEvent event : events) {
                UsageCall call = parseCall(event);
                if (call == null) {
                    continue;
                }
                totals.add(call);
                String key = call.provider + "|" + call.model + "|" + call.apiMode;
                byModel.computeIfAbsent(key, ignored -> new SummaryAccumulator(call.provider, call.model, call.apiMode)).add(call);
                calls.add(call.toMap());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("callCount", totals.callCount);
        result.put("totals", totals.toMap());
        result.put("byModel", byModel.values().stream().map(SummaryAccumulator::toMap).toList());
        result.put("calls", calls);
        return result;
    }

    public static Map<String, Object> summarizeTotals(List<AgentEvent> events) {
        Object totals = summarize(events).get("totals");
        if (totals instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    private static UsageCall parseCall(AgentEvent event) {
        if (event == null || event.payload() == null || event.payload().isNull() || !"model.usage".equals(event.type())) {
            return null;
        }
        JsonNode payload = event.payload();
        JsonNode tokenNode = payload.path("tokenUsage");
        if (!tokenNode.isObject()) {
            tokenNode = payload;
        }
        Long promptTokens = longValue(tokenNode, "promptTokens");
        Long completionTokens = longValue(tokenNode, "completionTokens");
        Long totalTokens = longValue(tokenNode, "totalTokens");
        BigDecimal promptCostUsd = decimalValue(payload, "promptCostUsd");
        BigDecimal completionCostUsd = decimalValue(payload, "completionCostUsd");
        BigDecimal totalCostUsd = decimalValue(payload, "totalCostUsd");
        if (totalTokens == null && (promptTokens != null || completionTokens != null)) {
            totalTokens = defaultLong(promptTokens) + defaultLong(completionTokens);
        }
        if (promptTokens == null && completionTokens == null && totalTokens == null && promptCostUsd == null && completionCostUsd == null && totalCostUsd == null) {
            return null;
        }
        return new UsageCall(
            textValue(payload, "provider"),
            textValue(payload, "model"),
            textValue(payload, "apiMode"),
            promptTokens,
            completionTokens,
            totalTokens,
            promptCostUsd,
            completionCostUsd,
            totalCostUsd,
            textValue(payload, "currency"),
            event.timestamp() == null ? "" : event.timestamp().toString(),
            event.taskId() == null ? "" : event.taskId()
        );
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal decimalValue(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.decimalValue().setScale(8, RoundingMode.HALF_UP);
        }
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.asText().trim()).setScale(8, RoundingMode.HALF_UP);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String textValue(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private record UsageCall(String provider,
                             String model,
                             String apiMode,
                             Long promptTokens,
                             Long completionTokens,
                             Long totalTokens,
                             BigDecimal promptCostUsd,
                             BigDecimal completionCostUsd,
                             BigDecimal totalCostUsd,
                             String currency,
                             String timestamp,
                             String taskId) {

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("provider", provider);
            map.put("model", model);
            map.put("apiMode", apiMode);
            map.put("promptTokens", defaultLong(promptTokens));
            map.put("completionTokens", defaultLong(completionTokens));
            map.put("totalTokens", defaultLong(totalTokens));
            map.put("promptCostUsd", promptCostUsd);
            map.put("completionCostUsd", completionCostUsd);
            map.put("totalCostUsd", totalCostUsd);
            map.put("currency", currency == null ? "" : currency);
            map.put("timestamp", timestamp == null ? "" : timestamp);
            map.put("taskId", taskId == null ? "" : taskId);
            return map;
        }
    }

    private static final class SummaryAccumulator {
        private final String provider;
        private final String model;
        private final String apiMode;
        private long callCount;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private BigDecimal promptCostUsd = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        private BigDecimal completionCostUsd = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        private BigDecimal totalCostUsd = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        private boolean hasCost;

        private SummaryAccumulator(String provider, String model, String apiMode) {
            this.provider = provider == null ? "" : provider;
            this.model = model == null ? "" : model;
            this.apiMode = apiMode == null ? "" : apiMode;
        }

        private void add(UsageCall call) {
            callCount++;
            promptTokens += defaultLong(call.promptTokens());
            completionTokens += defaultLong(call.completionTokens());
            totalTokens += defaultLong(call.totalTokens());
            if (call.promptCostUsd() != null) {
                promptCostUsd = promptCostUsd.add(call.promptCostUsd());
                hasCost = true;
            }
            if (call.completionCostUsd() != null) {
                completionCostUsd = completionCostUsd.add(call.completionCostUsd());
                hasCost = true;
            }
            if (call.totalCostUsd() != null) {
                totalCostUsd = totalCostUsd.add(call.totalCostUsd());
                hasCost = true;
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (!provider.isBlank()) {
                map.put("provider", provider);
            }
            if (!model.isBlank()) {
                map.put("model", model);
            }
            if (!apiMode.isBlank()) {
                map.put("apiMode", apiMode);
            }
            map.put("callCount", callCount);
            map.put("promptTokens", promptTokens);
            map.put("completionTokens", completionTokens);
            map.put("totalTokens", totalTokens);
            map.put("promptCostUsd", hasCost ? promptCostUsd : null);
            map.put("completionCostUsd", hasCost ? completionCostUsd : null);
            map.put("totalCostUsd", hasCost ? totalCostUsd : null);
            map.put("currency", hasCost ? "USD" : "");
            return map;
        }
    }
}