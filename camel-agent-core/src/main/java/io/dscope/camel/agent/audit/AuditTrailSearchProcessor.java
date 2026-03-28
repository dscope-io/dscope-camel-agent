package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

public class AuditTrailSearchProcessor implements Processor {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 2000;

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;
    private final AgentPlanSelectionResolver planSelectionResolver;
    private final String plansConfig;
    private final String blueprintUri;

    public AuditTrailSearchProcessor(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
        this(persistenceFacade, objectMapper, null, null, null);
    }

    public AuditTrailSearchProcessor(PersistenceFacade persistenceFacade,
                                     ObjectMapper objectMapper,
                                     AgentPlanSelectionResolver planSelectionResolver,
                                     String plansConfig,
                                     String blueprintUri) {
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
        this.planSelectionResolver = planSelectionResolver;
        this.plansConfig = plansConfig == null || plansConfig.isBlank() ? null : plansConfig;
        this.blueprintUri = blueprintUri == null || blueprintUri.isBlank() ? null : blueprintUri;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Message in = exchange.getIn();
        String conversationId = readText(in, "conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            writeBadRequest(exchange, "conversationId query parameter is required");
            return;
        }

        String type = readText(in, "type");
        String query = readText(in, "q");
        String fromText = readText(in, "from");
        String toText = readText(in, "to");
        int limit = parseLimit(readText(in, "limit"));

        Instant from = parseInstantOrNull(fromText);
        if (fromText != null && !fromText.isBlank() && from == null) {
            writeBadRequest(exchange, "Invalid from timestamp. Use ISO-8601 format, e.g. 2026-02-25T23:00:00Z");
            return;
        }
        Instant to = parseInstantOrNull(toText);
        if (toText != null && !toText.isBlank() && to == null) {
            writeBadRequest(exchange, "Invalid to timestamp. Use ISO-8601 format, e.g. 2026-02-25T23:59:59Z");
            return;
        }

        List<AgentEvent> loaded = persistenceFacade.loadConversation(conversationId, limit);
        String effectiveBlueprint = resolveBlueprint(conversationId);
        AuditMetadataSupport.BlueprintMetadata blueprintMetadata = AuditMetadataSupport.loadBlueprintMetadata(effectiveBlueprint);
        AuditMetadataSupport.AgentStepMetadata currentAgentState = AuditMetadataSupport.deriveAgentStepMetadata(loaded, effectiveBlueprint);
        Map<String, Object> conversationMetadata = AuditMetadataSupport.buildConversationMetadata(
            conversationId,
            loaded,
            blueprintMetadata,
            currentAgentState
        );
        List<Map<String, Object>> events = projectEvents(loaded, type, query, from, to, limit, effectiveBlueprint);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("ai", currentAgentState == null || currentAgentState.ai() == null ? Map.of() : currentAgentState.ai().asMap());
        response.put("conversationMetadata", conversationMetadata);
        response.put("modelUsage", AuditUsageSupport.summarize(loaded));
        response.put("filters", Map.of(
            "type", type == null ? "" : type,
            "q", query == null ? "" : query,
            "from", fromText == null ? "" : fromText,
            "to", toText == null ? "" : toText,
            "limit", limit
        ));
        response.put("count", events.size());
        response.put("events", events);

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private static String readText(Message in, String headerName) {
        Object value = in.getHeader(headerName);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static int parseLimit(String limitText) {
        if (limitText == null || limitText.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int parsed = Integer.parseInt(limitText.trim());
            return Math.max(1, Math.min(parsed, MAX_LIMIT));
        } catch (NumberFormatException ignored) {
            return DEFAULT_LIMIT;
        }
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private List<Map<String, Object>> projectEvents(List<AgentEvent> loaded,
                                                    String type,
                                                    String query,
                                                    Instant from,
                                                    Instant to,
                                                    int limit,
                                                    String effectiveBlueprint) {
        List<Map<String, Object>> projected = new ArrayList<>();
        String normalizedType = type == null || type.isBlank() ? null : type.trim().toLowerCase(Locale.ROOT);
        String normalizedQuery = query == null || query.isBlank() ? null : query.toLowerCase(Locale.ROOT);
        AuditMetadataSupport.AgentStepMetadata agentState = AuditMetadataSupport.AgentStepMetadata.fromBlueprint(
            effectiveBlueprint,
            AuditMetadataSupport.loadBlueprintMetadata(effectiveBlueprint)
        );

        for (AgentEvent event : loaded) {
            agentState = AuditMetadataSupport.advanceAgentStepMetadata(agentState, event);
            if (!matchesFilters(event, normalizedType, normalizedQuery, from, to)) {
                continue;
            }
            projected.add(toJson(event, agentState));
            if (projected.size() >= limit) {
                break;
            }
        }
        return projected;
    }

    private boolean matchesFilters(AgentEvent event,
                                   String normalizedType,
                                   String normalizedQuery,
                                   Instant from,
                                   Instant to) {
        if (event == null) {
            return false;
        }
        if (normalizedType != null && (event.type() == null || !event.type().toLowerCase(Locale.ROOT).equals(normalizedType))) {
            return false;
        }
        if (from != null && (event.timestamp() == null || event.timestamp().isBefore(from))) {
            return false;
        }
        if (to != null && (event.timestamp() == null || event.timestamp().isAfter(to))) {
            return false;
        }
        if (normalizedQuery != null
            && !containsIgnoreCase(event.conversationId(), normalizedQuery)
            && !containsIgnoreCase(event.taskId(), normalizedQuery)
            && !containsIgnoreCase(event.type(), normalizedQuery)
            && !containsIgnoreCase(String.valueOf(event.payload()), normalizedQuery)) {
            return false;
        }
        return true;
    }

    private Map<String, Object> toJson(AgentEvent event, AuditMetadataSupport.AgentStepMetadata agentState) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", event.conversationId());
        data.put("taskId", event.taskId());
        data.put("type", event.type());
        data.put("payload", event.payload());
        data.put("timestamp", event.timestamp() == null ? null : event.timestamp().toString());
        data.put("agent", agentState == null ? Map.of() : agentState.asMap());
        return data;
    }

    private void writeBadRequest(Exchange exchange, String message) throws Exception {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(Map.of(
            "error", "bad_request",
            "message", message
        )));
    }

    private String resolveBlueprint(String conversationId) {
        if (planSelectionResolver == null) {
            return blueprintUri;
        }
        return planSelectionResolver.resolveBlueprintForConversation(conversationId, plansConfig, blueprintUri);
    }
}
