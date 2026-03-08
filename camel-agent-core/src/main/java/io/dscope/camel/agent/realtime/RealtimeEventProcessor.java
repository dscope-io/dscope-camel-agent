package io.dscope.camel.agent.realtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.AuditGranularity;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.RealtimeSpec;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.agent.runtime.ConversationArchiveService;
import io.dscope.camel.agent.runtime.RealtimeConfigResolver;
import io.dscope.camel.agent.runtime.ResolvedAgentPlan;
import io.dscope.camel.agent.runtime.RuntimeControlState;
import io.dscope.camel.agent.util.TextEncodingSupport;

public class RealtimeEventProcessor implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeEventProcessor.class);

    private static final String DEFAULT_MODEL = "gpt-4o-realtime-preview";
    private static final String DEFAULT_ENDPOINT = "wss://api.openai.com/v1/realtime";
    private static final String DEFAULT_TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe";
    private static final String SESSION_REGISTRY_BEAN = "supportRealtimeSessionRegistry";
    private static final String PERSISTENCE_FACADE_BEAN = "persistenceFacade";
    private static final int MAX_RECENT_TURNS = 16;

    private final RealtimeRelayClient realtimeRelayClient;
    private final String defaultAgentEndpointUri;
    private final ObjectMapper objectMapper;
    private final MarkdownBlueprintLoader markdownBlueprintLoader;
    private final Map<String, RealtimeSpec> blueprintRealtimeCache;
    private final Map<String, Long> lastRealtimeResponseCreateAt;

    public RealtimeEventProcessor(RealtimeRelayClient realtimeRelayClient, String defaultAgentEndpointUri) {
        this.realtimeRelayClient = realtimeRelayClient;
        this.defaultAgentEndpointUri = defaultAgentEndpointUri;
        this.objectMapper = new ObjectMapper();
        this.markdownBlueprintLoader = new MarkdownBlueprintLoader();
        this.blueprintRealtimeCache = new ConcurrentHashMap<>();
        this.lastRealtimeResponseCreateAt = new ConcurrentHashMap<>();
    }

    public void clearBlueprintRealtimeCache() {
        blueprintRealtimeCache.clear();
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String conversationId = firstNonBlank(
            exchange.getMessage().getHeader("conversationId", String.class),
            exchange.getMessage().getHeader("sessionId", String.class),
            UUID.randomUUID().toString()
        );

        JsonNode root = parseJsonBody(exchange);
        ResolvedAgentPlan resolvedPlan = resolvePlan(exchange, conversationId, root);
        String eventType = firstNonBlank(text(root, "type"), "unknown");
        String transcript = firstNonBlank(
            text(root, "transcript"),
            text(root, "text"),
            text(root.path("payload"), "text"),
            text(root.path("payload"), "transcript")
        );

        LOGGER.debug("Realtime event received: conversationId={}, eventType={}, transcriptChars={}",
            conversationId,
            eventType,
            transcript.length());

        var response = objectMapper.createObjectNode()
            .put("conversationId", conversationId)
            .put("eventType", eventType)
            .put("accepted", true);

        ResolvedRealtimeConfig config = resolveRealtimeConfig(exchange);
        AuditGranularity auditGranularity = resolveAuditGranularity(exchange);
        if (shouldPersistRealtimeIngress(eventType, auditGranularity)) {
            persistRealtimeIngressEvent(exchange, conversationId, eventType, root);
        }

        if ("transcript.observed".equals(eventType)) {
            archiveObservedRealtimeTranscript(exchange, conversationId, root);
            String direction = firstNonBlank(text(root, "direction"), text(root.path("payload"), "direction"));
            boolean relayManaged = root.path("relayManaged").asBoolean(false)
                || root.path("payload").path("relayManaged").asBoolean(false);
            if (relayManaged && "input".equalsIgnoreCase(direction) && !transcript.isBlank()) {
                populateTranscriptRouteResponse(exchange, response, conversationId, transcript, auditGranularity, "voice-transcript");
                writeJson(exchange, response);
                return;
            }
            if (relayManaged && "output".equalsIgnoreCase(direction) && !transcript.isBlank()) {
                var aguiMessages = objectMapper.createArrayNode();
                aguiMessages.add(objectMapper.createObjectNode()
                    .put("role", "assistant")
                    .put("content", transcript)
                    .put("source", "voice-output-transcript"));
                response.set("aguiMessages", aguiMessages);
                response.put("routedToAgent", false);
                writeJson(exchange, response);
                return;
            }
        }

        if ("session.start".equals(eventType)) {
            try {
                connect(conversationId, config);
                LOGGER.info("Realtime session.start connected: conversationId={}, endpoint={}, model={}",
                    conversationId,
                    config.endpointUri(),
                    config.model());
            } catch (Exception connectFailure) {
                LOGGER.warn("Realtime session.start failed: conversationId={}, error={}",
                    conversationId,
                    connectFailure.getMessage() == null ? connectFailure.getClass().getSimpleName() : connectFailure.getMessage());
                response.put("accepted", false);
                response.put("connected", false);
                response.put("relayError", connectFailure.getMessage() == null ? "session.start failed" : connectFailure.getMessage());
                writeJson(exchange, response);
                return;
            }
            ObjectNode incomingSession = root.path("session").isObject()
                ? (ObjectNode) root.path("session").deepCopy()
                : objectMapper.createObjectNode();
            ObjectNode sessionPayload = restoreSessionStartPayload(exchange, conversationId, incomingSession, config);
            seedPlanMetadata(sessionPayload, resolvedPlan);
            if (sessionPayload.isObject() && !sessionPayload.isEmpty()) {
                var sessionUpdate = objectMapper.createObjectNode();
                sessionUpdate.put("type", "session.update");
                sessionUpdate.set("session", sessionPayload);
                try {
                    realtimeRelayClient.sendEvent(conversationId, sessionUpdate.toString());
                    LOGGER.debug("Realtime session.update forwarded: conversationId={}, language={}",
                        conversationId,
                        config.language());
                } catch (Exception sessionUpdateFailure) {
                    LOGGER.warn("Realtime session.update forwarding failed: conversationId={}, error={}",
                        conversationId,
                        sessionUpdateFailure.getMessage() == null
                            ? sessionUpdateFailure.getClass().getSimpleName()
                            : sessionUpdateFailure.getMessage());
                    response.put("relayError", sessionUpdateFailure.getMessage() == null
                        ? "session.update failed"
                        : sessionUpdateFailure.getMessage());
                }
            }
            response.put("connected", true);
            response.set("relayState", realtimeRelayClient.sessionState(conversationId));
            writeJson(exchange, response);
            return;
        }

        if ("session.close".equals(eventType) || "session.stop".equals(eventType)) {
            realtimeRelayClient.close(conversationId);
            lastRealtimeResponseCreateAt.remove(conversationId);
            LOGGER.info("Realtime session closed by event: conversationId={}, eventType={}", conversationId, eventType);
            response.put("connected", false);
            writeJson(exchange, response);
            return;
        }

        if ("session.state".equals(eventType)) {
            JsonNode relayState = realtimeRelayClient.sessionState(conversationId);
            LOGGER.info("Realtime session.state diagnostics: conversationId={}, {}",
                conversationId,
                summarizeRelayState(relayState));
            response.set("relayState", relayState);
            writeJson(exchange, response);
            return;
        }

        if (isRelayEvent(eventType)) {
            try {
                if (!realtimeRelayClient.isConnected(conversationId)) {
                    LOGGER.info("Realtime relay disconnected before event, reconnecting: conversationId={}, eventType={}",
                        conversationId,
                        eventType);
                    connect(conversationId, config);
                }
                realtimeRelayClient.sendEvent(conversationId, root.toString());
                LOGGER.debug("Realtime relay event forwarded: conversationId={}, eventType={}", conversationId, eventType);
                response.put("connected", realtimeRelayClient.isConnected(conversationId));
            } catch (Exception relayFailure) {
                LOGGER.warn("Realtime relay event forwarding failed: conversationId={}, eventType={}, error={}",
                    conversationId,
                    eventType,
                    relayFailure.getMessage() == null ? relayFailure.getClass().getSimpleName() : relayFailure.getMessage());
                response.put("accepted", false);
                response.put("connected", false);
                response.put("relayError", relayFailure.getMessage() == null ? "relay event failed" : relayFailure.getMessage());
            }
            response.set("relayState", realtimeRelayClient.sessionState(conversationId));
        }

        if ("transcript.final".equals(eventType) && !transcript.isBlank()) {
            populateTranscriptRouteResponse(exchange, response, conversationId, transcript, auditGranularity, "voice-transcript");
        } else if ("transcript.final".equals(eventType)) {
            LOGGER.warn("Realtime transcript.final received without transcript text: conversationId={}", conversationId);
        } else {
            response.put("routedToAgent", false);
            LOGGER.debug("Realtime event not routed to agent: conversationId={}, eventType={}", conversationId, eventType);
        }

        writeJson(exchange, response);
    }

    private void populateTranscriptRouteResponse(Exchange exchange,
                                                ObjectNode response,
                                                String conversationId,
                                                String transcript,
                                                AuditGranularity auditGranularity,
                                                String userMessageSource) {
        LOGGER.info("Realtime transcript received: conversationId={}, chars={}", conversationId, transcript.length());
        ProducerTemplate template = exchange.getContext().createProducerTemplate();
        RouteAssistantResult routeResult = routeTranscriptToAssistant(exchange, template, conversationId, transcript);
        String assistant = routeResult.assistantMessage();
        if (shouldPersistRealtimeTurns(auditGranularity) && !routeResult.turnEventsPersisted()) {
            persistRealtimeTurnEvents(exchange, conversationId, transcript, assistant);
        }
        archiveRealtimeTurn(exchange, conversationId, transcript, assistant);
        response.put("assistantMessage", assistant == null ? "" : assistant);
        ObjectNode realtimeSessionUpdate = composeRealtimeSessionUpdate(routeResult.sessionUpdate(), transcript, assistant);
        LOGGER.info("Realtime context update composed: conversationId={}, routePatchPresent={}, keys={}, transcriptChars={}, assistantChars={}",
            conversationId,
            routeResult.sessionUpdate() != null && !routeResult.sessionUpdate().isEmpty(),
            summarizeObjectKeys(realtimeSessionUpdate),
            transcript.length(),
            assistant == null ? 0 : assistant.length());
        boolean sessionContextUpdated = applyRouteSessionUpdate(exchange, conversationId, realtimeSessionUpdate);
        response.put("sessionContextUpdated", sessionContextUpdated);
        LOGGER.info("Realtime context update apply result: conversationId={}, updated={}",
            conversationId,
            sessionContextUpdated);
        boolean realtimeVoiceBranchStarted = false;
        if (sessionContextUpdated) {
            realtimeVoiceBranchStarted = maybeStartRealtimeVoiceBranch(conversationId);
        } else {
            LOGGER.info("Realtime voice branch skipped because session context update was not applied: conversationId={}",
                conversationId);
        }
        response.put("realtimeVoiceBranchStarted", realtimeVoiceBranchStarted);
        var aguiMessages = objectMapper.createArrayNode();
        aguiMessages.add(objectMapper.createObjectNode()
            .put("role", "user")
            .put("content", transcript)
            .put("source", firstNonBlank(userMessageSource, "voice-transcript")));
        if (assistant != null && !assistant.isBlank()) {
            aguiMessages.add(buildAssistantAgUiMessage(assistant));
        }
        response.set("aguiMessages", aguiMessages);
        response.put("routedToAgent", true);
        LOGGER.info("Realtime transcript routed: conversationId={}, assistantChars={}",
            conversationId,
            assistant == null ? 0 : assistant.length());
    }

    private void persistRealtimeTurnEvents(Exchange exchange,
                                           String conversationId,
                                           String transcript,
                                           String assistant) {
        persistRealtimeMessageEvent(exchange, conversationId, "user.message", transcript);
        persistRealtimeMessageEvent(exchange, conversationId, "agent.message", assistant);
    }

    private void persistRealtimeMessageEvent(Exchange exchange,
                                             String conversationId,
                                             String eventType,
                                             String payloadText) {
        if (payloadText == null || payloadText.isBlank()) {
            return;
        }
        PersistenceFacade persistenceFacade = lookupPersistenceFacade(exchange);
        if (persistenceFacade == null) {
            return;
        }
        try {
            persistenceFacade.appendEvent(
                new AgentEvent(
                    conversationId,
                    null,
                    eventType,
                    objectMapper.getNodeFactory().textNode(payloadText),
                    Instant.now()
                ),
                UUID.randomUUID().toString()
            );
        } catch (Exception persistenceFailure) {
            LOGGER.warn("Realtime message event persistence failed: conversationId={}, eventType={}, error={}",
                conversationId,
                eventType,
                persistenceFailure.getMessage() == null
                    ? persistenceFailure.getClass().getSimpleName()
                    : persistenceFailure.getMessage());
        }
    }

    private void archiveObservedRealtimeTranscript(Exchange exchange, String conversationId, JsonNode root) {
        ConversationArchiveService archiveService = lookupConversationArchiveService(exchange);
        if (archiveService == null) {
            return;
        }
        String transcript = firstNonBlank(
            text(root, "transcript"),
            text(root, "text"),
            text(root.path("payload"), "transcript"),
            text(root.path("payload"), "text")
        );
        String direction = firstNonBlank(text(root, "direction"), text(root.path("payload"), "direction"));
        String observedType = firstNonBlank(
            text(root, "observedEventType"),
            text(root, "eventType"),
            text(root.path("payload"), "observedEventType"),
            text(root.path("payload"), "eventType")
        );
        LOGGER.info("Realtime transcript observed: conversationId={}, direction={}, observedType={}, chars={}",
            conversationId,
            direction.isBlank() ? "unknown" : direction,
            observedType.isBlank() ? "unknown" : observedType,
            transcript.length());
        archiveService.appendRealtimeTranscriptObserved(conversationId, direction, transcript, observedType);
    }

    private String summarizeRelayState(JsonNode relayState) {
        if (relayState == null || relayState.isNull() || relayState.isMissingNode()) {
            return "relayState=missing";
        }
        boolean connected = relayState.path("connected").asBoolean(false);
        String lastError = text(relayState, "lastError");
        JsonNode events = relayState.path("events");
        int eventCount = events.isArray() ? events.size() : 0;
        boolean hasInputTranscript = false;
        boolean hasResponseTextDone = false;
        String lastEventType = "";
        if (events.isArray()) {
            for (JsonNode event : events) {
                String type = firstNonBlank(text(event, "type"), text(event.path("data"), "type")).toLowerCase();
                if (type.isBlank()) {
                    continue;
                }
                lastEventType = type;
                if (type.contains("input_audio_transcription") || type.contains("transcription.completed")) {
                    hasInputTranscript = true;
                }
                if ("response.text.done".equals(type) || "response.audio_transcript.done".equals(type)) {
                    hasResponseTextDone = true;
                }
            }
        }
        return String.format("connected=%s, lastError=%s, eventCount=%d, hasInputTranscript=%s, hasResponseTextDone=%s, lastEventType=%s",
            connected,
            lastError == null || lastError.isBlank() ? "none" : lastError,
            eventCount,
            hasInputTranscript,
            hasResponseTextDone,
            lastEventType.isBlank() ? "none" : lastEventType);
    }

    private void archiveRealtimeTurn(Exchange exchange, String conversationId, String transcript, String assistant) {
        ConversationArchiveService archiveService = lookupConversationArchiveService(exchange);
        if (archiveService == null) {
            return;
        }
        archiveService.appendRealtimeTurn(conversationId, transcript, assistant);
    }

    private ConversationArchiveService lookupConversationArchiveService(Exchange exchange) {
        return exchange.getContext().getRegistry()
            .lookupByNameAndType("conversationArchiveService", ConversationArchiveService.class);
    }

    private JsonNode buildAssistantAgUiMessage(String assistantContent) {
        var assistantMessage = objectMapper.createObjectNode()
            .put("role", "assistant")
            .put("content", assistantContent)
            .put("source", "agent");

        JsonNode parsed = parseJson(assistantContent);
        if (parsed != null && parsed.isObject() && !parsed.isEmpty() && parsed.hasNonNull("ticketId")) {
            var widget = objectMapper.createObjectNode()
                .put("template", "ticket-card");
            var data = objectMapper.createObjectNode()
                .put("ticketId", parsed.path("ticketId").asText(""))
                .put("status", parsed.path("status").asText("OPEN"))
                .put("summary", parsed.path("summary").asText(""))
                .put("assignedQueue", parsed.path("assignedQueue").asText("L1-SUPPORT"))
                .put("message", parsed.path("message").asText(""));
            widget.set("data", data);
            assistantMessage.set("widget", widget);
        }

        return assistantMessage;
    }

    private boolean maybeStartRealtimeVoiceBranch(String conversationId) {
        if (!realtimeRelayClient.isConnected(conversationId)) {
            LOGGER.debug("Realtime voice branch skipped (relay not connected): conversationId={}", conversationId);
            return false;
        }

        long now = System.currentTimeMillis();
        long last = lastRealtimeResponseCreateAt.getOrDefault(conversationId, 0L);
        if ((now - last) < 1500L) {
            LOGGER.debug("Realtime voice branch skipped (throttled): conversationId={}, elapsedMs={}",
                conversationId,
                now - last);
            return false;
        }

        var responseCreate = objectMapper.createObjectNode();
        responseCreate.put("type", "response.create");
        var responseBody = objectMapper.createObjectNode();
        var modalities = objectMapper.createArrayNode();
        modalities.add("audio");
        modalities.add("text");
        responseBody.set("modalities", modalities);
        responseCreate.set("response", responseBody);
        try {
            realtimeRelayClient.sendEvent(conversationId, responseCreate.toString());
            lastRealtimeResponseCreateAt.put(conversationId, now);
            LOGGER.info("Realtime voice branch started from backend: conversationId={}", conversationId);
            return true;
        } catch (Exception sendFailure) {
            LOGGER.warn("Realtime voice branch start failed: conversationId={}, error={}",
                conversationId,
                sendFailure.getMessage() == null ? sendFailure.getClass().getSimpleName() : sendFailure.getMessage());
            return false;
        }
    }

    private RouteAssistantResult routeTranscriptToAssistant(Exchange exchange,
                                                            ProducerTemplate template,
                                                            String conversationId,
                                                            String transcript) {
        if (isTicketPrompt(transcript)) {
            try {
                String ticketResponse = template.requestBody("direct:support-ticket-open", Map.of("query", transcript), String.class);
                if (ticketResponse != null && !ticketResponse.isBlank()) {
                    LOGGER.info("Realtime transcript routed via ticket tool: conversationId={}", conversationId);
                    return new RouteAssistantResult(ticketResponse, extractSessionUpdate(null, ticketResponse), false);
                }
            } catch (Exception ticketRouteFailure) {
                LOGGER.debug("Realtime ticket-route fallback unavailable: conversationId={}, reason={}",
                    conversationId,
                    ticketRouteFailure.getMessage() == null
                        ? ticketRouteFailure.getClass().getSimpleName()
                        : ticketRouteFailure.getMessage());
            }
        }
        Exchange routeExchange = template.request(resolveAgentEndpoint(exchange), routed -> {
            routed.getMessage().setBody(transcript);
            routed.getMessage().setHeader(AgentHeaders.CONVERSATION_ID, conversationId);
            copyHeader(exchange, routed, AgentHeaders.PLAN_NAME);
            copyHeader(exchange, routed, AgentHeaders.PLAN_VERSION);
        });
        String assistant = routeExchange.getMessage().getBody(String.class);
        assistant = maybeCanonicalizeTicketAssistantMessage(template, conversationId, transcript, assistant);
        boolean turnEventsPersisted = Boolean.TRUE.equals(
            routeExchange.getProperty(AgentHeaders.RESPONSE_TURN_EVENTS_PERSISTED, Boolean.class)
        );
        return new RouteAssistantResult(assistant, extractSessionUpdate(routeExchange, assistant), turnEventsPersisted);
    }

    private String maybeCanonicalizeTicketAssistantMessage(ProducerTemplate template,
                                                           String conversationId,
                                                           String transcript,
                                                           String assistantMessage) {
        if (assistantMessage == null || assistantMessage.isBlank()) {
            return assistantMessage;
        }

        JsonNode parsed = parseJson(assistantMessage);
        if (parsed == null || !parsed.isObject() || parsed.isEmpty()) {
            return assistantMessage;
        }

        String ticketId = text(parsed, "ticketId");
        if (!ticketId.isBlank()) {
            return assistantMessage;
        }

        boolean ticketLikeStructuredPayload = parsed.has("issueDescription")
            || parsed.has("issue_description")
            || parsed.has("problem")
            || parsed.has("issue");
        if (!ticketLikeStructuredPayload) {
            return assistantMessage;
        }

        String ticketSummary = firstNonBlank(
            text(parsed, "issueDescription"),
            text(parsed, "issue_description"),
            text(parsed, "summary"),
            text(parsed, "description"),
            text(parsed, "issue"),
            text(parsed, "problem"),
            text(parsed, "text"),
            transcript
        );

        if (ticketSummary.isBlank()) {
            return assistantMessage;
        }

        try {
            String canonicalTicketJson = template.requestBody("direct:support-ticket-open", Map.of("query", ticketSummary), String.class);
            JsonNode canonicalParsed = parseJson(canonicalTicketJson);
            if (canonicalParsed != null && canonicalParsed.isObject() && !text(canonicalParsed, "ticketId").isBlank()) {
                LOGGER.info("Realtime assistant payload canonicalized to ticket schema: conversationId={}", conversationId);
                return canonicalTicketJson;
            }
        } catch (Exception canonicalizeFailure) {
            LOGGER.debug("Realtime assistant ticket canonicalization skipped: conversationId={}, reason={}",
                conversationId,
                canonicalizeFailure.getMessage() == null
                    ? canonicalizeFailure.getClass().getSimpleName()
                    : canonicalizeFailure.getMessage());
        }

        return assistantMessage;
    }

    private boolean applyRouteSessionUpdate(Exchange exchange, String conversationId, ObjectNode sessionUpdate) {
        if (conversationId == null || conversationId.isBlank() || sessionUpdate == null || sessionUpdate.isEmpty()) {
            return false;
        }
        RealtimeBrowserSessionRegistry sessionRegistry = lookupSessionRegistry(exchange);
        if (sessionRegistry == null) {
            LOGGER.debug("Realtime route session update skipped (registry missing): conversationId={}", conversationId);
            return false;
        }
        sessionRegistry.mergeSession(conversationId, sessionUpdate);
        LOGGER.info("Realtime route session update applied: conversationId={}, keys={}",
            conversationId,
            summarizeObjectKeys(sessionUpdate));
        return true;
    }

    private ObjectNode composeRealtimeSessionUpdate(ObjectNode routeSessionUpdate, String transcript, String assistant) {
        ObjectNode update = routeSessionUpdate == null
            ? objectMapper.createObjectNode()
            : routeSessionUpdate.deepCopy();
        ObjectNode metadata = ensureObject(update, "metadata");
        ObjectNode camelAgent = ensureObject(metadata, "camelAgent");
        ObjectNode context = ensureObject(camelAgent, "context");
        camelAgent.put("routeTriggered", true);
        camelAgent.put("lastTranscript", transcript == null ? "" : transcript);
        camelAgent.put("lastAssistantMessage", assistant == null ? "" : assistant);
        camelAgent.put("updatedAt", Instant.now().toString());
        ArrayNode recentTurns = ensureArray(context, "recentTurns");
        appendRecentTurn(recentTurns, "user", transcript);
        appendRecentTurn(recentTurns, "assistant", assistant);
        trimRecentTurns(recentTurns, MAX_RECENT_TURNS);

        JsonNode structuredAssistantPayload = parseJson(assistant);
        if (structuredAssistantPayload != null && structuredAssistantPayload.isObject() && !structuredAssistantPayload.isEmpty()) {
            context.set("lastAssistantPayload", structuredAssistantPayload.deepCopy());
        }

        context.put("lastUpdatedAt", Instant.now().toString());
        return update;
    }

    private void seedPlanMetadata(ObjectNode sessionPayload, ResolvedAgentPlan resolvedPlan) {
        if (sessionPayload == null || resolvedPlan == null || resolvedPlan.legacyMode()) {
            return;
        }
        ObjectNode metadata = ensureObject(sessionPayload, "metadata");
        ObjectNode camelAgent = ensureObject(metadata, "camelAgent");
        ObjectNode plan = ensureObject(camelAgent, "plan");
        plan.put("name", resolvedPlan.planName());
        plan.put("version", resolvedPlan.planVersion());
        plan.put("blueprint", resolvedPlan.blueprint());
        plan.put("resolvedAt", Instant.now().toString());
    }

    private ObjectNode ensureObject(ObjectNode parent, String fieldName) {
        JsonNode current = parent.get(fieldName);
        if (current instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode replacement = objectMapper.createObjectNode();
        parent.set(fieldName, replacement);
        return replacement;
    }

    private ArrayNode ensureArray(ObjectNode parent, String fieldName) {
        JsonNode current = parent.get(fieldName);
        if (current instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        ArrayNode replacement = objectMapper.createArrayNode();
        parent.set(fieldName, replacement);
        return replacement;
    }

    private void appendRecentTurn(ArrayNode turns, String role, String content) {
        if (turns == null || content == null || content.isBlank()) {
            return;
        }
        ObjectNode turn = objectMapper.createObjectNode();
        turn.put("role", role == null ? "unknown" : role);
        turn.put("text", content);
        turn.put("at", Instant.now().toString());
        turns.add(turn);
    }

    private void trimRecentTurns(ArrayNode turns, int maxTurns) {
        if (turns == null || maxTurns <= 0) {
            return;
        }
        while (turns.size() > maxTurns) {
            turns.remove(0);
        }
    }

    private void persistRealtimeIngressEvent(Exchange exchange, String conversationId, String eventType, JsonNode payload) {
        PersistenceFacade persistenceFacade = lookupPersistenceFacade(exchange);
        if (persistenceFacade == null) {
            LOGGER.debug("Realtime ingress event persistence skipped: conversationId={}, eventType={}, reason=noPersistenceFacade",
                conversationId,
                eventType);
            return;
        }
        try {
            persistenceFacade.appendEvent(
                new AgentEvent(
                    conversationId,
                    null,
                    "realtime." + eventType,
                    payload == null ? objectMapper.nullNode() : payload.deepCopy(),
                    Instant.now()
                ),
                UUID.randomUUID().toString()
            );
            LOGGER.debug("Realtime ingress event persisted: conversationId={}, eventType={}", conversationId, eventType);
        } catch (Exception persistenceFailure) {
            LOGGER.warn("Realtime ingress event persistence failed: conversationId={}, eventType={}, error={}",
                conversationId,
                eventType,
                persistenceFailure.getMessage() == null
                    ? persistenceFailure.getClass().getSimpleName()
                    : persistenceFailure.getMessage());
        }
    }

    private String summarizeObjectKeys(ObjectNode node) {
        if (node == null || node.isEmpty()) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        var fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            out.append(fieldNames.next());
            if (fieldNames.hasNext()) {
                out.append(',');
            }
        }
        out.append(']');
        return out.toString();
    }

    private PersistenceFacade lookupPersistenceFacade(Exchange exchange) {
        return exchange.getContext().getRegistry()
            .lookupByNameAndType(PERSISTENCE_FACADE_BEAN, PersistenceFacade.class);
    }

    private RealtimeBrowserSessionRegistry lookupSessionRegistry(Exchange exchange) {
        RealtimeBrowserSessionRegistry fromRegistry = exchange.getContext().getRegistry()
            .lookupByNameAndType(SESSION_REGISTRY_BEAN, RealtimeBrowserSessionRegistry.class);
        if (fromRegistry != null) {
            return fromRegistry;
        }

        RealtimeBrowserSessionInitProcessor initProcessor = exchange.getContext().getRegistry()
            .lookupByNameAndType("supportRealtimeSessionInitProcessor", RealtimeBrowserSessionInitProcessor.class);
        if (initProcessor != null && initProcessor.sessionRegistry() != null) {
            return initProcessor.sessionRegistry();
        }

        RealtimeBrowserTokenProcessor tokenProcessor = exchange.getContext().getRegistry()
            .lookupByNameAndType("supportRealtimeTokenProcessor", RealtimeBrowserTokenProcessor.class);
        if (tokenProcessor != null && tokenProcessor.sessionRegistry() != null) {
            return tokenProcessor.sessionRegistry();
        }

        return null;
    }

    private ObjectNode restoreSessionStartPayload(Exchange exchange,
                                                  String conversationId,
                                                  ObjectNode incomingSession,
                                                  ResolvedRealtimeConfig config) {
        RealtimeBrowserSessionRegistry sessionRegistry = lookupSessionRegistry(exchange);
        ObjectNode restoredSession = sessionRegistry == null ? null : sessionRegistry.getSession(conversationId);
        ObjectNode sessionPayload;
        if (restoredSession != null && !restoredSession.isEmpty()) {
            sessionPayload = restoredSession.deepCopy();
        } else {
            sessionPayload = objectMapper.createObjectNode();
        }

        if (incomingSession != null && !incomingSession.isEmpty()) {
            mergeObject(sessionPayload, incomingSession);
        }

        applyConfiguredSessionDefaults(sessionPayload, config);
        ensureSessionMetadataConversationId(sessionPayload, conversationId);

        if (sessionRegistry != null && !sessionPayload.isEmpty()) {
            sessionRegistry.putSession(conversationId, sessionPayload);
        }

        LOGGER.info("Realtime session.start payload restored: conversationId={}, restoredContextPresent={}, incomingOverrides={}, keys={}",
            conversationId,
            restoredSession != null && !restoredSession.isEmpty(),
            incomingSession != null && !incomingSession.isEmpty(),
            summarizeObjectKeys(sessionPayload));

        return sessionPayload;
    }

    private void ensureSessionMetadataConversationId(ObjectNode sessionPayload, String conversationId) {
        if (sessionPayload == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        ObjectNode metadata = ensureObject(sessionPayload, "metadata");
        if (!metadata.hasNonNull("conversationId")) {
            metadata.put("conversationId", conversationId);
        }
    }

    private void mergeObject(ObjectNode target, JsonNode source) {
        if (target == null || source == null || !source.isObject()) {
            return;
        }
        source.properties().forEach(entry -> {
            String field = entry.getKey();
            JsonNode incoming = entry.getValue();
            JsonNode existing = target.get(field);
            if (incoming != null && incoming.isObject() && existing != null && existing.isObject()) {
                mergeObject((ObjectNode) existing, incoming);
            } else if (incoming != null) {
                target.set(field, incoming.deepCopy());
            }
        });
    }

    private ObjectNode extractSessionUpdate(Exchange routeExchange, String assistantBody) {
        ObjectNode fromHeader = routeExchange == null ? null : firstObjectNode(
            routeExchange.getMessage().getHeader("realtimeSessionUpdate"),
            routeExchange.getMessage().getHeader("realtime.session.update"),
            routeExchange.getMessage().getHeader("realtime.session.context.update"),
            routeExchange.getMessage().getHeader("sessionUpdate"),
            routeExchange.getProperty("realtimeSessionUpdate"),
            routeExchange.getProperty("realtime.session.update"),
            routeExchange.getProperty("sessionUpdate")
        );
        if (fromHeader != null) {
            return fromHeader;
        }

        JsonNode parsedAssistant = parseJson(assistantBody);
        if (!parsedAssistant.isObject()) {
            return null;
        }
        ObjectNode fromBody = firstObjectNode(
            parsedAssistant.path("realtimeSessionUpdate"),
            parsedAssistant.path("realtimeSession"),
            parsedAssistant.path("sessionUpdate")
        );
        return fromBody == null ? null : fromBody.deepCopy();
    }

    private ObjectNode firstObjectNode(Object... candidates) {
        for (Object candidate : candidates) {
            ObjectNode converted = asObjectNode(candidate);
            if (converted != null) {
                return converted;
            }
        }
        return null;
    }

    private ObjectNode asObjectNode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ObjectNode objectNode) {
            return objectNode;
        }
        if (value instanceof JsonNode jsonNode && jsonNode.isObject()) {
            return (ObjectNode) jsonNode;
        }
        if (value instanceof Map<?, ?> map) {
            JsonNode converted = objectMapper.valueToTree(map);
            return converted.isObject() ? (ObjectNode) converted : null;
        }
        if (value instanceof String text && !text.isBlank()) {
            JsonNode parsed = parseJson(text);
            return parsed.isObject() ? (ObjectNode) parsed : null;
        }
        return null;
    }

    private boolean isTicketPrompt(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return false;
        }
        String normalized = transcript.toLowerCase();
        return normalized.contains("ticket")
            || normalized.contains("open")
            || normalized.contains("create")
            || normalized.contains("submit")
            || normalized.contains("escalate");
    }

    private void connect(String conversationId, ResolvedRealtimeConfig config) throws Exception {
        if (!"openai".equalsIgnoreCase(config.provider())) {
            throw new IllegalStateException("Unsupported realtime provider: " + config.provider());
        }
        LOGGER.debug("Realtime connect requested: conversationId={}, provider={}, endpoint={}, model={}",
            conversationId,
            config.provider(),
            config.endpointUri(),
            config.model());
        realtimeRelayClient.connect(
            conversationId,
            config.endpointUri(),
            config.model(),
            config.apiKey(),
            config.reconnectPolicy()
        );
    }

    private void applyConfiguredSessionDefaults(JsonNode sessionPayload, ResolvedRealtimeConfig config) {
        if (sessionPayload == null || !sessionPayload.isObject()) {
            return;
        }

        var session = (com.fasterxml.jackson.databind.node.ObjectNode) sessionPayload;
        if (!session.hasNonNull("voice") && !config.voice().isBlank()) {
            session.put("voice", config.voice());
        }
        if (!session.hasNonNull("input_audio_format") && !config.inputAudioFormat().isBlank()) {
            session.put("input_audio_format", config.inputAudioFormat());
        }
        if (!session.hasNonNull("output_audio_format") && !config.outputAudioFormat().isBlank()) {
            session.put("output_audio_format", config.outputAudioFormat());
        }

        ObjectNode inputAudioTranscription = ensureObject(session, "input_audio_transcription");
        if (!inputAudioTranscription.hasNonNull("model")) {
            inputAudioTranscription.put("model", DEFAULT_TRANSCRIPTION_MODEL);
        }

        boolean turnDetectionDisabled = session.has("turn_detection") && session.path("turn_detection").isNull();
        if (!turnDetectionDisabled) {
            ObjectNode turnDetection = ensureObject(session, "turn_detection");
            if (!turnDetection.hasNonNull("type")) {
                turnDetection.put("type", "server_vad");
            }
            if (!turnDetection.hasNonNull("create_response")) {
                turnDetection.put("create_response", false);
            }
        } else {
            LOGGER.info("Realtime session config keeps turn_detection disabled (top-level)");
        }

        ObjectNode audio = ensureObject(session, "audio");
        ObjectNode audioInput = ensureObject(audio, "input");
        ObjectNode nestedTranscription = ensureObject(audioInput, "transcription");
        if (!nestedTranscription.hasNonNull("model")) {
            nestedTranscription.put("model", DEFAULT_TRANSCRIPTION_MODEL);
        }
        if (!nestedTranscription.hasNonNull("language") && !config.language().isBlank()) {
            nestedTranscription.put("language", config.language());
        }

        boolean nestedTurnDetectionDisabled = audioInput.has("turn_detection") && audioInput.path("turn_detection").isNull();
        if (!nestedTurnDetectionDisabled) {
            ObjectNode nestedTurnDetection = ensureObject(audioInput, "turn_detection");
            if (!nestedTurnDetection.hasNonNull("type")) {
                nestedTurnDetection.put("type", "server_vad");
            }
            if (!nestedTurnDetection.hasNonNull("create_response")) {
                nestedTurnDetection.put("create_response", false);
            }
        } else {
            LOGGER.info("Realtime session config keeps turn_detection disabled (audio.input)");
        }

        ObjectNode audioOutput = ensureObject(audio, "output");
        if (!audioOutput.hasNonNull("voice") && !config.voice().isBlank()) {
            audioOutput.put("voice", config.voice());
        }

    }

    private ResolvedRealtimeConfig resolveRealtimeConfig(Exchange exchange) {
        RealtimeSpec blueprintRealtime = loadBlueprintRealtime(exchange);
        RealtimeSpec resolvedRealtime = RealtimeConfigResolver.resolve(blueprintRealtime, key -> propertyOrNull(exchange, key));

        String provider = firstNonBlank(
            resolvedRealtime == null ? null : resolvedRealtime.provider(),
            "openai"
        );
        String model = firstNonBlank(
            resolvedRealtime == null ? null : resolvedRealtime.model(),
            DEFAULT_MODEL
        );
        String endpointUri = firstNonBlank(
            resolvedRealtime == null ? null : resolvedRealtime.endpointUri(),
            DEFAULT_ENDPOINT
        );
        String voice = firstNonBlank(
            resolvedRealtime == null ? null : resolvedRealtime.voice(),
            propertyOrNull(exchange, "agent.runtime.realtime.voice"),
            propertyOrNull(exchange, "agent.realtime.voice")
        );
        String inputAudioFormat = firstNonBlank(
            resolvedRealtime == null ? null : resolvedRealtime.inputAudioFormat(),
            propertyOrNull(exchange, "agent.runtime.realtime.input-audio-format"),
            propertyOrNull(exchange, "agent.runtime.realtime.inputAudioFormat"),
            propertyOrNull(exchange, "agent.realtime.input-audio-format"),
            propertyOrNull(exchange, "agent.realtime.inputAudioFormat")
        );
        String outputAudioFormat = firstNonBlank(
            resolvedRealtime == null ? null : resolvedRealtime.outputAudioFormat(),
            propertyOrNull(exchange, "agent.runtime.realtime.output-audio-format"),
            propertyOrNull(exchange, "agent.runtime.realtime.outputAudioFormat"),
            propertyOrNull(exchange, "agent.realtime.output-audio-format"),
            propertyOrNull(exchange, "agent.realtime.outputAudioFormat")
        );
        String language = firstNonBlank(
            propertyOrNull(exchange, "agent.runtime.realtime.language"),
            propertyOrNull(exchange, "agent.realtime.language")
        );

        RealtimeReconnectPolicy reconnectPolicy = new RealtimeReconnectPolicy(
            intOrDefault(resolvedRealtime == null ? null : resolvedRealtime.reconnectMaxSendRetries(), 3),
            intOrDefault(resolvedRealtime == null ? null : resolvedRealtime.reconnectMaxReconnects(), 8),
            longOrDefault(resolvedRealtime == null ? null : resolvedRealtime.reconnectInitialBackoffMs(), 150L),
            longOrDefault(resolvedRealtime == null ? null : resolvedRealtime.reconnectMaxBackoffMs(), 2_000L)
        ).normalized();

        String apiKey = firstNonBlank(
            property(exchange, "agent.runtime.spring-ai.openai.api-key", ""),
            property(exchange, "spring.ai.openai.api-key", ""),
            property(exchange, "openai.api.key", ""),
            System.getenv("OPENAI_API_KEY")
        );

        LOGGER.debug("Realtime config resolved: provider={}, model={}, endpoint={}, voice={}, language={}, apiKeyPresent={}, reconnectPolicy={}/{}/{}/{}",
            provider,
            model,
            endpointUri,
            voice,
            language,
            apiKey != null && !apiKey.isBlank(),
            reconnectPolicy.maxSendRetries(),
            reconnectPolicy.maxReconnectsPerSession(),
            reconnectPolicy.initialBackoffMs(),
            reconnectPolicy.maxBackoffMs());

        return new ResolvedRealtimeConfig(provider, model, endpointUri, apiKey, reconnectPolicy, voice, inputAudioFormat, outputAudioFormat, language);
    }

    private RealtimeSpec loadBlueprintRealtime(Exchange exchange) {
        String blueprintLocation = firstNonBlank(
            exchange.getMessage().getHeader(AgentHeaders.RESOLVED_BLUEPRINT, String.class),
            property(exchange, "agent.blueprint", "")
        );
        if (blueprintLocation.isBlank()) {
            return null;
        }
        try {
            return blueprintRealtimeCache.computeIfAbsent(blueprintLocation, this::loadRealtimeSpec);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private RealtimeSpec loadRealtimeSpec(String location) {
        try {
            AgentBlueprint blueprint = markdownBlueprintLoader.load(location);
            return blueprint.realtime();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveAgentEndpoint(Exchange exchange) {
        String fromHeader = exchange.getMessage().getHeader("agentEndpointUri", String.class);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        String configured = firstNonBlank(
            propertyOrNull(exchange, "agent.runtime.realtime.agent-endpoint-uri"),
            propertyOrNull(exchange, "agent.runtime.realtime.agentEndpointUri"),
            propertyOrNull(exchange, "agent.realtime.agent-endpoint-uri"),
            propertyOrNull(exchange, "agent.realtime.agentEndpointUri")
        );
        return configured == null || configured.isBlank() ? defaultAgentEndpointUri : configured;
    }

    private ResolvedAgentPlan resolvePlan(Exchange exchange, String conversationId, JsonNode root) {
        AgentPlanSelectionResolver resolver = exchange.getContext().getRegistry().findSingleByType(AgentPlanSelectionResolver.class);
        if (resolver == null) {
            ResolvedAgentPlan legacy = ResolvedAgentPlan.legacy(property(exchange, "agent.blueprint", ""));
            exchange.getMessage().setHeader(AgentHeaders.RESOLVED_BLUEPRINT, legacy.blueprint());
            return legacy;
        }
        String requestedPlanName = firstNonBlank(
            exchange.getMessage().getHeader(AgentHeaders.PLAN_NAME, String.class),
            text(root, "planName"),
            text(root.path("payload"), "planName")
        );
        String requestedPlanVersion = firstNonBlank(
            exchange.getMessage().getHeader(AgentHeaders.PLAN_VERSION, String.class),
            text(root, "planVersion"),
            text(root.path("payload"), "planVersion")
        );
        if (!requestedPlanName.isBlank()) {
            exchange.getMessage().setHeader(AgentHeaders.PLAN_NAME, requestedPlanName);
        }
        if (!requestedPlanVersion.isBlank()) {
            exchange.getMessage().setHeader(AgentHeaders.PLAN_VERSION, requestedPlanVersion);
        }
        ResolvedAgentPlan resolved = resolver.resolve(
            conversationId,
            requestedPlanName.isBlank() ? null : requestedPlanName,
            requestedPlanVersion.isBlank() ? null : requestedPlanVersion,
            property(exchange, "agent.agents-config", ""),
            property(exchange, "agent.blueprint", "")
        );
        if (!resolved.legacyMode()) {
            exchange.getMessage().setHeader(AgentHeaders.RESOLVED_PLAN_NAME, resolved.planName());
            exchange.getMessage().setHeader(AgentHeaders.RESOLVED_PLAN_VERSION, resolved.planVersion());
        }
        exchange.getMessage().setHeader(AgentHeaders.RESOLVED_BLUEPRINT, resolved.blueprint());
        return resolved;
    }

    private void copyHeader(Exchange from, Exchange to, String headerName) {
        Object value = from.getMessage().getHeader(headerName);
        if (value != null) {
            to.getMessage().setHeader(headerName, value);
        }
    }

    private boolean isRelayEvent(String eventType) {
        return eventType != null && (
            eventType.startsWith("input_audio_buffer.")
                || eventType.startsWith("conversation.item.")
                || eventType.startsWith("response.")
                || eventType.startsWith("session.")
        );
    }

    private void writeJson(Exchange exchange, JsonNode body) {
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(body.toString());
    }

    private JsonNode parseJsonBody(Exchange exchange) {
        try {
            byte[] bodyBytes = exchange.getMessage().getBody(byte[].class);
            if (bodyBytes != null && bodyBytes.length > 0) {
                return TextEncodingSupport.repairUtf8Mojibake(objectMapper.readTree(bodyBytes), objectMapper);
            }
        } catch (Exception ignored) {
            // Fall back to the String-based path below.
        }
        return parseJson(exchange.getMessage().getBody(String.class));
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return TextEncodingSupport.repairUtf8Mojibake(objectMapper.readTree(body), objectMapper);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode().put("text", TextEncodingSupport.repairUtf8Mojibake(body));
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String propertyOrNull(Exchange exchange, String key) {
        String value = property(exchange, key, "");
        return value.isBlank() ? null : value;
    }

    private String property(Exchange exchange, String key, String defaultValue) {
        try {
            String value = exchange.getContext().resolvePropertyPlaceholders("{{" + key + ":" + defaultValue + "}}");
            if (value == null || value.isBlank() || value.contains("{{")) {
                return defaultValue;
            }
            return value.trim();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private int intOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private long longOrDefault(Long value, long defaultValue) {
        return value == null ? defaultValue : value;
    }

    private AuditGranularity resolveAuditGranularity(Exchange exchange) {
        RuntimeControlState runtimeControlState = exchange.getContext().getRegistry()
            .lookupByNameAndType("runtimeControlState", RuntimeControlState.class);
        if (runtimeControlState != null) {
            return runtimeControlState.auditGranularity();
        }
        return AuditGranularity.from(property(exchange, "agent.audit.granularity", "debug"));
    }

    private boolean shouldPersistRealtimeTurns(AuditGranularity granularity) {
        return granularity == AuditGranularity.INFO || granularity == AuditGranularity.DEBUG;
    }

    private boolean shouldPersistRealtimeIngress(String eventType, AuditGranularity granularity) {
        String normalizedType = eventType == null ? "" : eventType.toLowerCase();
        return switch (granularity) {
            case NONE -> false;
            case ERROR -> normalizedType.contains("error") || normalizedType.contains("failed");
            case INFO -> "transcript.final".equals(normalizedType)
                || "transcript.observed".equals(normalizedType)
                || "session.start".equals(normalizedType)
                || "session.stop".equals(normalizedType)
                || "session.close".equals(normalizedType)
                || normalizedType.contains("error");
            case DEBUG -> true;
        };
    }

    private record ResolvedRealtimeConfig(
        String provider,
        String model,
        String endpointUri,
        String apiKey,
        RealtimeReconnectPolicy reconnectPolicy,
        String voice,
        String inputAudioFormat,
        String outputAudioFormat,
        String language
    ) {
        private ResolvedRealtimeConfig {
            voice = voice == null ? "" : voice;
            inputAudioFormat = inputAudioFormat == null ? "" : inputAudioFormat;
            outputAudioFormat = outputAudioFormat == null ? "" : outputAudioFormat;
            language = language == null ? "" : language;
        }
    }

    private record RouteAssistantResult(String assistantMessage, ObjectNode sessionUpdate, boolean turnEventsPersisted) {
    }
}
