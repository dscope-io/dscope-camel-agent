package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

public class AuditConversationListProcessor implements Processor {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final int EVENT_SCAN_LIMIT = 300;

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;
    private final AgentPlanSelectionResolver planSelectionResolver;
    private final String plansConfig;
    private final String blueprintUri;

    public AuditConversationListProcessor(PersistenceFacade persistenceFacade,
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
        int limit = parseLimit(readText(in, "limit"));
        String query = normalizeQuery(readText(in, "q"));
        String topic = normalizeQuery(readText(in, "topic"));
        String sortBy = normalizeSortBy(readText(in, "sortBy"));
        boolean ascending = isAscending(readText(in, "order"));

        List<String> conversationIds = persistenceFacade.listConversationIds(limit * 5);
        List<ConversationListItem> matchedItems = new ArrayList<>();
        for (String conversationId : conversationIds) {
            List<AgentEvent> events = persistenceFacade.loadConversation(conversationId, EVENT_SCAN_LIMIT);
            String effectiveBlueprint = resolveBlueprint(conversationId);
            AuditMetadataSupport.BlueprintMetadata blueprintMetadata = AuditMetadataSupport.loadBlueprintMetadata(effectiveBlueprint);
            AuditMetadataSupport.AgentStepMetadata agentStepMetadata = AuditMetadataSupport.deriveAgentStepMetadata(events, effectiveBlueprint);
            Map<String, Object> metadata = AuditMetadataSupport.buildConversationMetadata(
                conversationId,
                events,
                blueprintMetadata,
                agentStepMetadata
            );
            metadata = new LinkedHashMap<>(metadata);
            metadata.put("blueprintUri", effectiveBlueprint == null ? "" : effectiveBlueprint);
            if (!matchesQuery(query, metadata, conversationId)) {
                continue;
            }
            if (!matchesTopic(topic, metadata)) {
                continue;
            }
            matchedItems.add(new ConversationListItem(conversationId, metadata));
        }

        matchedItems.sort(comparatorFor(sortBy, ascending));

        List<Map<String, Object>> items = matchedItems.stream()
            .limit(limit)
            .map(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("conversationId", item.conversationId());
                row.put("metadata", item.metadata());
                return row;
            })
            .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query == null ? "" : query);
        response.put("topic", topic == null ? "" : topic);
        response.put("sortBy", sortBy);
        response.put("order", ascending ? "asc" : "desc");
        response.put("limit", limit);
        response.put("count", items.size());
        response.put("blueprintUri", blueprintUri == null ? "" : blueprintUri);
        response.put("items", items);

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

    private static String normalizeQuery(String query) {
        return query == null || query.isBlank() ? null : query.toLowerCase(Locale.ROOT).trim();
    }

    private static String normalizeSortBy(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "lastEventAt";
        }
        String normalized = sortBy.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "title" -> "title";
            case "eventcount" -> "eventCount";
            case "lasteventat" -> "lastEventAt";
            default -> "lastEventAt";
        };
    }

    private static boolean isAscending(String order) {
        if (order == null || order.isBlank()) {
            return false;
        }
        String normalized = order.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("asc") || normalized.equals("ascending");
    }

    private static boolean matchesQuery(String query, Map<String, Object> metadata, String conversationId) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (containsIgnoreCase(conversationId, query)) {
            return true;
        }
        if (containsIgnoreCase(String.valueOf(metadata.get("title")), query)) {
            return true;
        }
        if (containsIgnoreCase(String.valueOf(metadata.get("description")), query)) {
            return true;
        }
        Object topics = metadata.get("topics");
        if (topics instanceof List<?> list) {
            for (Object topic : list) {
                if (containsIgnoreCase(String.valueOf(topic), query)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesTopic(String topic, Map<String, Object> metadata) {
        if (topic == null || topic.isBlank()) {
            return true;
        }
        Object topics = metadata.get("topics");
        if (!(topics instanceof List<?> list)) {
            return false;
        }
        for (Object entry : list) {
            if (containsIgnoreCase(String.valueOf(entry), topic)) {
                return true;
            }
        }
        return false;
    }

    private static Comparator<ConversationListItem> comparatorFor(String sortBy, boolean ascending) {
        Comparator<ConversationListItem> comparator = switch (sortBy) {
            case "title" -> Comparator.comparing(item -> stringValue(item.metadata().get("title")), String.CASE_INSENSITIVE_ORDER);
            case "eventCount" -> Comparator.comparingInt(item -> intValue(item.metadata().get("eventCount")));
            default -> Comparator.comparing(item -> stringValue(item.metadata().get("lastEventAt")), String.CASE_INSENSITIVE_ORDER);
        };
        return ascending ? comparator : comparator.reversed();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private String resolveBlueprint(String conversationId) {
        if (planSelectionResolver == null) {
            return blueprintUri;
        }
        return planSelectionResolver.resolveBlueprintForConversation(conversationId, plansConfig, blueprintUri);
    }

    private record ConversationListItem(String conversationId, Map<String, Object> metadata) {
    }
}
