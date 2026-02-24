package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.RealtimeSpec;
import io.dscope.camel.agent.runtime.RealtimeConfigResolver;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RealtimeEventProcessor implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeEventProcessor.class);

    private static final String DEFAULT_MODEL = "gpt-4o-realtime-preview";
    private static final String DEFAULT_ENDPOINT = "wss://api.openai.com/v1/realtime";
    private static final String SESSION_REGISTRY_BEAN = "supportRealtimeSessionRegistry";

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

    @Override
    public void process(Exchange exchange) throws Exception {
        String conversationId = firstNonBlank(
            exchange.getMessage().getHeader("conversationId", String.class),
            exchange.getMessage().getHeader("sessionId", String.class),
            UUID.randomUUID().toString()
        );

        JsonNode root = parseJson(exchange.getMessage().getBody(String.class));
        String eventType = firstNonBlank(text(root, "type"), "unknown");
        String transcript = firstNonBlank(
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
            var sessionPayload = root.path("session").isObject()
                ? root.path("session").deepCopy()
                : objectMapper.createObjectNode();
            applyConfiguredSessionDefaults(sessionPayload, config);
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
            LOGGER.debug("Realtime session.state requested: conversationId={}", conversationId);
            response.set("relayState", realtimeRelayClient.sessionState(conversationId));
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
            LOGGER.info("Realtime transcript received: conversationId={}, chars={}", conversationId, transcript.length());
            boolean realtimeVoiceBranchStarted = maybeStartRealtimeVoiceBranch(conversationId);
            response.put("realtimeVoiceBranchStarted", realtimeVoiceBranchStarted);
            ProducerTemplate template = exchange.getContext().createProducerTemplate();
            RouteAssistantResult routeResult = routeTranscriptToAssistant(exchange, template, conversationId, transcript);
            String assistant = routeResult.assistantMessage();
            response.put("assistantMessage", assistant == null ? "" : assistant);
            response.put("sessionContextUpdated", applyRouteSessionUpdate(exchange, conversationId, routeResult.sessionUpdate()));
            var aguiMessages = objectMapper.createArrayNode();
            var transcriptMessage = objectMapper.createObjectNode()
                .put("role", "user")
                .put("content", transcript)
                .put("source", "voice-transcript");
            aguiMessages.add(transcriptMessage);
            if (assistant != null && !assistant.isBlank()) {
                var assistantMessage = buildAssistantAgUiMessage(assistant);
                aguiMessages.add(assistantMessage);
            }
            response.set("aguiMessages", aguiMessages);
            response.put("routedToAgent", true);
            LOGGER.info("Realtime transcript routed: conversationId={}, assistantChars={}",
                conversationId,
                assistant == null ? 0 : assistant.length());
        } else {
            response.put("routedToAgent", false);
            LOGGER.debug("Realtime event not routed to agent: conversationId={}, eventType={}", conversationId, eventType);
        }

        writeJson(exchange, response);
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
                    return new RouteAssistantResult(ticketResponse, extractSessionUpdate(null, ticketResponse));
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
        });
        String assistant = routeExchange.getMessage().getBody(String.class);
        return new RouteAssistantResult(assistant, extractSessionUpdate(routeExchange, assistant));
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
        LOGGER.debug("Realtime route session update applied: conversationId={}, fields={}",
            conversationId,
            sessionUpdate.fieldNames().hasNext());
        return true;
    }

    private RealtimeBrowserSessionRegistry lookupSessionRegistry(Exchange exchange) {
        return exchange.getContext().getRegistry()
            .lookupByNameAndType(SESSION_REGISTRY_BEAN, RealtimeBrowserSessionRegistry.class);
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
        String blueprintLocation = property(exchange, "agent.blueprint", "");
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

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode().put("text", body);
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

    private record RouteAssistantResult(String assistantMessage, ObjectNode sessionUpdate) {
    }
}
