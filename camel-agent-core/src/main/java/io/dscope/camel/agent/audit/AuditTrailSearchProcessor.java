package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
        String rootConversationId = readText(in, "rootConversationId");
        if ((conversationId == null || conversationId.isBlank()) && (rootConversationId == null || rootConversationId.isBlank())) {
            writeBadRequest(exchange, "conversationId or rootConversationId query parameter is required");
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

        if (conversationId != null && !conversationId.isBlank()) {
            writeSingleConversationResponse(exchange, conversationId, type, query, fromText, toText, from, to, limit);
            return;
        }

        writeRootChainResponse(exchange, rootConversationId, type, query, fromText, toText, from, to, limit);
    }

    private void writeSingleConversationResponse(Exchange exchange,
                                                 String conversationId,
                                                 String type,
                                                 String query,
                                                 String fromText,
                                                 String toText,
                                                 Instant from,
                                                 Instant to,
                                                 int limit) throws Exception {
        List<AgentEvent> loaded = persistenceFacade.loadConversation(conversationId, limit);
        String effectiveBlueprint = resolveBlueprint(conversationId);
        AuditMetadataSupport.BlueprintMetadata blueprintMetadata = AuditMetadataSupport.loadBlueprintMetadata(effectiveBlueprint);
        AuditMetadataSupport.AgentStepMetadata currentAgentState = AuditMetadataSupport.deriveAgentStepMetadata(loaded, effectiveBlueprint);
        AuditMetadataSupport.SipMetadata currentSipState = AuditMetadataSupport.deriveSipMetadata(loaded);
        Map<String, Object> conversationMetadata = AuditMetadataSupport.buildConversationMetadata(
            conversationId,
            loaded,
            blueprintMetadata,
            currentAgentState
        );
        List<Map<String, Object>> events = projectEvents(loaded, type, query, from, to, limit, effectiveBlueprint);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("rootConversationId", conversationMetadata.getOrDefault("a2aRootConversationId", ""));
        response.put("ai", currentAgentState == null || currentAgentState.ai() == null ? Map.of() : currentAgentState.ai().asMap());
        response.put("conversationMetadata", conversationMetadata);
        response.put("modelUsage", AuditUsageSupport.summarize(loaded));
        response.put("sip", currentSipState.asMap());
        response.put("filters", Map.of(
            "type", type == null ? "" : type,
            "q", query == null ? "" : query,
            "from", fromText == null ? "" : fromText,
            "to", toText == null ? "" : toText,
            "limit", limit,
            "rootConversationId", ""
        ));
        response.put("count", events.size());
        response.put("events", events);

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private void writeRootChainResponse(Exchange exchange,
                                        String rootConversationId,
                                        String type,
                                        String query,
                                        String fromText,
                                        String toText,
                                        Instant from,
                                        Instant to,
                                        int limit) throws Exception {
        List<ConversationAuditSlice> slices = loadChainSlices(rootConversationId);
        List<Map<String, Object>> projected = new ArrayList<>();
        List<Map<String, Object>> conversations = new ArrayList<>();
        List<Map<String, Object>> hops = new ArrayList<>();
        List<AgentEvent> allEvents = new ArrayList<>();

        for (ConversationAuditSlice slice : slices) {
            allEvents.addAll(slice.events());
            Map<String, Object> conversation = new LinkedHashMap<>();
            conversation.put("conversationId", slice.conversationId());
            conversation.put("metadata", slice.metadata());
            conversations.add(conversation);
            hops.add(chainHop(slice));

            List<Map<String, Object>> conversationEvents = projectEvents(slice.events(), type, query, from, to, limit, slice.effectiveBlueprint());
            projected.addAll(conversationEvents);
        }

        projected.sort(Comparator.comparing(event -> String.valueOf(event.get("timestamp")), String.CASE_INSENSITIVE_ORDER));
        if (projected.size() > limit) {
            projected = new ArrayList<>(projected.subList(0, limit));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", "");
        response.put("rootConversationId", rootConversationId == null ? "" : rootConversationId);
        response.put("conversationCount", conversations.size());
        response.put("chainSummary", chainSummary(rootConversationId, slices, projected));
        response.put("hops", hops);
        response.put("conversations", conversations);
        response.put("exports", chainExports(rootConversationId, hops));
        response.put("conversationMetadata", Map.of(
            "conversationKind", "a2a-chain",
            "a2aRootConversationId", rootConversationId == null ? "" : rootConversationId
        ));
        response.put("modelUsage", AuditUsageSupport.summarize(allEvents));
        response.put("sip", Map.of());
        response.put("filters", Map.of(
            "type", type == null ? "" : type,
            "q", query == null ? "" : query,
            "from", fromText == null ? "" : fromText,
            "to", toText == null ? "" : toText,
            "limit", limit,
            "rootConversationId", rootConversationId == null ? "" : rootConversationId
        ));
        response.put("count", projected.size());
        response.put("events", projected);

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private static String readText(Message in, String headerName) {
        Object value = in.getHeader(headerName);
        if (value != null) {
            String text = String.valueOf(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        String query = in.getHeader(Exchange.HTTP_QUERY, String.class);
        return queryParameter(query, headerName);
    }

    private static String queryParameter(String query, String name) {
        if (query == null || query.isBlank() || name == null || name.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String rawName = separator >= 0 ? pair.substring(0, separator) : pair;
            if (!name.equals(urlDecode(rawName))) {
                continue;
            }
            String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
            String decoded = urlDecode(rawValue);
            return decoded.isBlank() ? null : decoded;
        }
        return null;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
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
        AuditMetadataSupport.SipMetadata sipState = AuditMetadataSupport.SipMetadata.empty();

        for (AgentEvent event : loaded) {
            agentState = AuditMetadataSupport.advanceAgentStepMetadata(agentState, event);
            sipState = AuditMetadataSupport.advanceSipMetadata(sipState, event);
            if (!matchesFilters(event, normalizedType, normalizedQuery, from, to)) {
                continue;
            }
            projected.add(toJson(event, agentState, sipState));
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
        return normalizedQuery == null
            || containsIgnoreCase(event.conversationId(), normalizedQuery)
            || containsIgnoreCase(event.taskId(), normalizedQuery)
            || containsIgnoreCase(event.type(), normalizedQuery)
            || containsIgnoreCase(String.valueOf(event.payload()), normalizedQuery);
    }

    private List<ConversationAuditSlice> loadChainSlices(String rootConversationId) {
        if (rootConversationId == null || rootConversationId.isBlank()) {
            return List.of();
        }
        List<ConversationAuditSlice> slices = new ArrayList<>();
        for (String candidateConversationId : persistenceFacade.listConversationIds(MAX_LIMIT)) {
            String effectiveBlueprint = resolveBlueprint(candidateConversationId);
            List<AgentEvent> candidateEvents = persistenceFacade.loadConversation(candidateConversationId, MAX_LIMIT);
            AuditMetadataSupport.BlueprintMetadata blueprintMetadata = AuditMetadataSupport.loadBlueprintMetadata(effectiveBlueprint);
            AuditMetadataSupport.AgentStepMetadata currentAgentState = AuditMetadataSupport.deriveAgentStepMetadata(candidateEvents, effectiveBlueprint);
            Map<String, Object> metadata = AuditMetadataSupport.buildConversationMetadata(
                candidateConversationId,
                candidateEvents,
                blueprintMetadata,
                currentAgentState
            );
            String candidateRoot = String.valueOf(metadata.getOrDefault("a2aRootConversationId", ""));
            if (!candidateRoot.equals(rootConversationId)) {
                continue;
            }
            slices.add(new ConversationAuditSlice(candidateConversationId, candidateEvents, metadata, effectiveBlueprint));
        }
        slices.sort(Comparator.comparing(slice -> String.valueOf(slice.metadata().get("firstEventAt")), String.CASE_INSENSITIVE_ORDER));
        return slices;
    }

    private Map<String, Object> chainSummary(String rootConversationId,
                                             List<ConversationAuditSlice> slices,
                                             List<Map<String, Object>> projected) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rootConversationId", rootConversationId == null ? "" : rootConversationId);
        summary.put("conversationCount", slices.size());
        summary.put("eventCount", projected.size());
        summary.put("firstEventAt", firstEventAt(slices));
        summary.put("lastEventAt", lastEventAt(slices));
        summary.put("entryConversationId", slices.isEmpty() ? "" : slices.get(0).conversationId());

        LinkedHashSet<String> agentIds = new LinkedHashSet<>();
        LinkedHashSet<String> agentNames = new LinkedHashSet<>();
        LinkedHashSet<String> planNames = new LinkedHashSet<>();
        for (ConversationAuditSlice slice : slices) {
            addIfPresent(agentIds, slice.metadata().get("a2aAgentId"));
            addIfPresent(agentNames, slice.metadata().get("agentName"));
            addIfPresent(planNames, slice.metadata().get("planName"));
        }
        summary.put("agentIds", List.copyOf(agentIds));
        summary.put("agentNames", List.copyOf(agentNames));
        summary.put("planNames", List.copyOf(planNames));
        return summary;
    }

    private Map<String, Object> chainHop(ConversationAuditSlice slice) {
        Map<String, Object> hop = new LinkedHashMap<>();
        Map<String, Object> metadata = slice.metadata();
        hop.put("conversationId", slice.conversationId());
        hop.put("parentConversationId", stringValue(metadata.get("a2aParentConversationId")));
        hop.put("rootConversationId", stringValue(metadata.get("a2aRootConversationId")));
        hop.put("linkedConversationId", stringValue(metadata.get("a2aLinkedConversationId")));
        hop.put("agentId", stringValue(metadata.get("a2aAgentId")));
        hop.put("agentName", stringValue(metadata.get("agentName")));
        hop.put("planName", stringValue(metadata.get("planName")));
        hop.put("planVersion", stringValue(metadata.get("planVersion")));
        hop.put("eventCount", metadata.getOrDefault("eventCount", 0));
        hop.put("firstEventAt", stringValue(metadata.get("firstEventAt")));
        hop.put("lastEventAt", stringValue(metadata.get("lastEventAt")));
        hop.put("children", childConversationIds(slice, metadata));
        return hop;
    }

    private Map<String, Object> chainExports(String rootConversationId, List<Map<String, Object>> hops) {
        Map<String, Object> exports = new LinkedHashMap<>();
        exports.put("graph", chainGraph(rootConversationId, hops));
        exports.put("csv", chainCsv(rootConversationId, hops));
        return exports;
    }

    private Map<String, Object> chainGraph(String rootConversationId, List<Map<String, Object>> hops) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        LinkedHashSet<String> nodeIds = new LinkedHashSet<>();
        for (Map<String, Object> hop : hops) {
            String conversationId = stringValue(hop.get("conversationId"));
            String agentName = stringValue(hop.get("agentName"));
            String planName = stringValue(hop.get("planName"));
            nodeIds.add(conversationId);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", conversationId);
            node.put("label", agentName.isBlank() ? conversationId : agentName);
            node.put("conversationId", conversationId);
            node.put("parentConversationId", stringValue(hop.get("parentConversationId")));
            node.put("rootConversationId", stringValue(hop.get("rootConversationId")));
            node.put("agentId", stringValue(hop.get("agentId")));
            node.put("agentName", agentName);
            node.put("planName", planName);
            node.put("planVersion", stringValue(hop.get("planVersion")));
            node.put("eventCount", hop.getOrDefault("eventCount", 0));
            node.put("firstEventAt", stringValue(hop.get("firstEventAt")));
            node.put("lastEventAt", stringValue(hop.get("lastEventAt")));
            nodes.add(node);

            String parentConversationId = stringValue(hop.get("parentConversationId"));
            if (!parentConversationId.isBlank() && nodeIds.contains(parentConversationId)) {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("id", parentConversationId + "->" + conversationId);
                edge.put("source", parentConversationId);
                edge.put("target", conversationId);
                edge.put("type", "a2a-parent-child");
                edges.add(edge);
            }
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("rootConversationId", rootConversationId == null ? "" : rootConversationId);
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    private Map<String, Object> chainCsv(String rootConversationId, List<Map<String, Object>> hops) {
        List<String> columns = List.of(
            "rootConversationId",
            "conversationId",
            "parentConversationId",
            "agentId",
            "agentName",
            "planName",
            "planVersion",
            "eventCount",
            "firstEventAt",
            "lastEventAt",
            "children"
        );
        List<List<String>> rows = new ArrayList<>();
        StringBuilder text = new StringBuilder(String.join(",", columns));
        for (Map<String, Object> hop : hops) {
            List<String> children = hop.get("children") instanceof List<?> rawChildren
                ? rawChildren.stream().map(String::valueOf).toList()
                : List.of();
            List<String> row = List.of(
                rootConversationId == null ? "" : rootConversationId,
                stringValue(hop.get("conversationId")),
                stringValue(hop.get("parentConversationId")),
                stringValue(hop.get("agentId")),
                stringValue(hop.get("agentName")),
                stringValue(hop.get("planName")),
                stringValue(hop.get("planVersion")),
                stringValue(hop.get("eventCount")),
                stringValue(hop.get("firstEventAt")),
                stringValue(hop.get("lastEventAt")),
                String.join("|", children)
            );
            rows.add(row);
            text.append("\n").append(row.stream().map(this::escapeCsv).reduce((left, right) -> left + "," + right).orElse(""));
        }
        Map<String, Object> csv = new LinkedHashMap<>();
        csv.put("columns", columns);
        csv.put("rows", rows);
        csv.put("text", text.toString());
        return csv;
    }

    private String escapeCsv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    private List<String> childConversationIds(ConversationAuditSlice currentSlice, Map<String, Object> metadata) {
        String currentConversationId = currentSlice.conversationId();
        List<String> children = new ArrayList<>();
        for (ConversationAuditSlice candidate : loadChainSlices(stringValue(metadata.get("a2aRootConversationId")))) {
            if (currentConversationId.equals(stringValue(candidate.metadata().get("a2aParentConversationId")))) {
                children.add(candidate.conversationId());
            }
        }
        return children;
    }

    private String firstEventAt(List<ConversationAuditSlice> slices) {
        return slices.stream()
            .map(slice -> stringValue(slice.metadata().get("firstEventAt")))
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse("");
    }

    private String lastEventAt(List<ConversationAuditSlice> slices) {
        for (int i = slices.size() - 1; i >= 0; i--) {
            String value = stringValue(slices.get(i).metadata().get("lastEventAt"));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void addIfPresent(LinkedHashSet<String> values, Object candidate) {
        String value = stringValue(candidate);
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> toJson(AgentEvent event,
                                       AuditMetadataSupport.AgentStepMetadata agentState,
                                       AuditMetadataSupport.SipMetadata sipState) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", event.conversationId());
        data.put("taskId", event.taskId());
        data.put("type", event.type());
        data.put("payload", event.payload());
        data.put("timestamp", event.timestamp() == null ? null : event.timestamp().toString());
        data.put("agent", agentState == null ? Map.of() : agentState.asMap());
        data.put("sip", sipState == null ? Map.of() : sipState.asMap());
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

    private record ConversationAuditSlice(String conversationId,
                                          List<AgentEvent> events,
                                          Map<String, Object> metadata,
                                          String effectiveBlueprint) {
    }
}
