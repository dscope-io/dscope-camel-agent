package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenAiRealtimeRelayClient implements RealtimeRelayClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiRealtimeRelayClient.class);

    private static final String DEFAULT_ENDPOINT = "wss://api.openai.com/v1/realtime";
    private static final RealtimeReconnectPolicy DEFAULT_RECONNECT_POLICY = new RealtimeReconnectPolicy(3, 8, 150L, 2_000L);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, RealtimeSession> sessions;

    public OpenAiRealtimeRelayClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.sessions = new ConcurrentHashMap<>();
    }

    @Override
    public boolean isConnected(String conversationId) {
        RealtimeSession session = sessions.get(conversationId);
        return session != null && session.open();
    }

    @Override
    public void connect(String conversationId, String endpointUri, String model, String apiKey) throws Exception {
        connect(conversationId, endpointUri, model, apiKey, DEFAULT_RECONNECT_POLICY);
    }

    @Override
    public void connect(String conversationId, String endpointUri, String model, String apiKey, RealtimeReconnectPolicy reconnectPolicy) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing OpenAI API key for realtime relay");
        }
        RealtimeReconnectPolicy policy = reconnectPolicy == null ? DEFAULT_RECONNECT_POLICY : reconnectPolicy.normalized();
        String modelName = (model == null || model.isBlank()) ? "gpt-4o-realtime-preview" : model;
        String endpoint = buildEndpoint(endpointUri, modelName);
        RealtimeSession existing = sessions.get(conversationId);
        if (existing != null && existing.open()) {
            LOGGER.debug("Realtime relay already connected: conversationId={}, endpoint={}", conversationId, existing.endpointUri());
            return;
        }

        RealtimeWebSocketListener listener = existing == null
            ? new RealtimeWebSocketListener()
            : existing.listener();
        WebSocket webSocket = createSocket(endpoint, apiKey, listener);
        listener.markConnected();
        RealtimeSession session = new RealtimeSession(conversationId, endpoint, modelName, apiKey, webSocket, listener, policy);
        if (existing != null) {
            session.copyReconnectFrom(existing);
            session.incrementReconnectCount();
        }
        sessions.put(conversationId, session);
        LOGGER.info("Realtime relay connected: conversationId={}, endpoint={}, model={}, reconnectCount={}",
            conversationId,
            endpoint,
            modelName,
            session.reconnectCount());
    }

    @Override
    public void sendEvent(String conversationId, String eventJson) {
        RealtimeSession session = requireSession(conversationId);
        int attempt = 0;
        while (true) {
            synchronized (session.listener().sendLock()) {
                try {
                    session.socket().sendText(eventJson, true).join();
                    session.listener().clearError();
                    LOGGER.debug("Realtime relay event sent: conversationId={}, bytes={}, attempt={}",
                        conversationId,
                        eventJson == null ? 0 : eventJson.length(),
                        attempt + 1);
                    return;
                } catch (Exception sendFailure) {
                    String reason = messageOf(sendFailure);
                    LOGGER.warn("Realtime relay send failed: conversationId={}, attempt={}, reason={}",
                        conversationId,
                        attempt + 1,
                        reason);
                    if (attempt >= session.reconnectPolicy().maxSendRetries()) {
                        session.listener().markError(reason);
                        LOGGER.error("Realtime relay send retries exhausted: conversationId={}, maxSendRetries={}, reason={}",
                            conversationId,
                            session.reconnectPolicy().maxSendRetries(),
                            reason);
                        throw sendFailure;
                    }
                    attempt++;
                    if (isSendPending(reason)) {
                        LOGGER.debug("Realtime relay send pending, retrying without reconnect: conversationId={}, attempt={}",
                            conversationId,
                            attempt);
                        sleepBeforePendingRetry(attempt);
                        session = requireSession(conversationId);
                        continue;
                    }
                    sleepBeforeReconnect(session.reconnectPolicy(), attempt);
                    reconnect(session, reason);
                    session = requireSession(conversationId);
                }
            }
        }
    }

    @Override
    public ArrayNode pollEvents(String conversationId) {
        RealtimeSession session = sessions.get(conversationId);
        ArrayNode events = objectMapper.createArrayNode();
        if (session == null) {
            return events;
        }
        List<String> drained = session.listener().drain();
        if (!drained.isEmpty()) {
            LOGGER.debug("Realtime relay events polled: conversationId={}, count={}", conversationId, drained.size());
        }
        for (String message : drained) {
            try {
                events.add(objectMapper.readTree(message));
            } catch (Exception ignored) {
                events.add(message);
            }
        }
        return events;
    }

    @Override
    public void close(String conversationId) {
        RealtimeSession session = sessions.remove(conversationId);
        if (session == null) {
            return;
        }
        try {
            session.socket().sendClose(WebSocket.NORMAL_CLOSURE, "session.close").join();
        } catch (Exception ignored) {
        }
        session.listener().markClosed(WebSocket.NORMAL_CLOSURE, "session.close");
        LOGGER.info("Realtime relay closed: conversationId={}", conversationId);
    }

    @Override
    public ObjectNode sessionState(String conversationId) {
        ObjectNode state = objectMapper.createObjectNode();
        RealtimeSession session = sessions.get(conversationId);
        state.put("connected", session != null && session.open());
        if (session != null) {
            state.put("reconnectCount", session.reconnectCount());
            state.put("lastError", session.listener().lastError());
            state.put("lastCloseCode", session.listener().lastCloseCode());
            state.put("lastCloseReason", session.listener().lastCloseReason());
            state.put("lastConnectedAt", session.listener().lastConnectedAt());
            state.put("lastDisconnectedAt", session.listener().lastDisconnectedAt());
        } else {
            state.put("reconnectCount", 0);
            state.put("lastError", "");
            state.put("lastCloseCode", 0);
            state.put("lastCloseReason", "");
            state.put("lastConnectedAt", "");
            state.put("lastDisconnectedAt", "");
        }
        state.set("events", pollEvents(conversationId));
        return state;
    }

    private void reconnect(RealtimeSession session, String reason) {
        RealtimeWebSocketListener listener = session.listener();
        int maxReconnects = session.reconnectPolicy().maxReconnectsPerSession();
        if (session.reconnectCount() >= maxReconnects) {
            String capMessage = "Reconnect limit reached (" + maxReconnects + ")";
            listener.markError(capMessage + ": " + reason);
            LOGGER.error("Realtime relay reconnect limit reached: conversationId={}, reconnectCount={}, maxReconnects={}, reason={}",
                session.conversationId(),
                session.reconnectCount(),
                maxReconnects,
                reason);
            throw new IllegalStateException(capMessage + " for conversationId=" + session.conversationId());
        }
        listener.markError("Send failed, reconnecting: " + reason);
        LOGGER.info("Realtime relay reconnecting: conversationId={}, attempt={}, reason={}",
            session.conversationId(),
            session.reconnectCount() + 1,
            reason);
        WebSocket oldSocket = session.socket();
        if (oldSocket != null) {
            try {
                oldSocket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect").join();
            } catch (RuntimeException ignored) {
            }
        }
        WebSocket newSocket = createSocket(session.endpointUri(), session.apiKey(), listener);
        listener.markConnected();
        RealtimeSession replacement = session.withSocket(newSocket);
        replacement.incrementReconnectCount();
        sessions.put(session.conversationId(), replacement);
        LOGGER.info("Realtime relay reconnected: conversationId={}, reconnectCount={}",
            session.conversationId(),
            replacement.reconnectCount());
    }

    private void sleepBeforeReconnect(RealtimeReconnectPolicy reconnectPolicy, int attempt) {
        long delay = reconnectPolicy.backoffDelayMs(attempt);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void sleepBeforePendingRetry(int attempt) {
        long delay = Math.min(25L * Math.max(1, attempt), 200L);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isSendPending(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.toLowerCase().contains("send pending");
    }

    private WebSocket createSocket(String endpoint, String apiKey, RealtimeWebSocketListener listener) {
        return httpClient.newWebSocketBuilder()
            .header("Authorization", "Bearer " + apiKey)
            .header("OpenAI-Beta", "realtime=v1")
            .buildAsync(URI.create(endpoint), listener)
            .join();
    }

    private String messageOf(Exception exception) {
        Throwable cause = exception.getCause();
        String message = cause != null ? cause.getMessage() : exception.getMessage();
        return message == null ? "unknown relay error" : message;
    }

    private RealtimeSession requireSession(String conversationId) {
        RealtimeSession session = sessions.get(conversationId);
        if (session == null || !session.open()) {
            LOGGER.warn("Realtime relay session unavailable: conversationId={}, hasSession={}, open={}",
                conversationId,
                session != null,
                session != null && session.open());
            throw new IllegalStateException("Realtime session is not connected for conversationId=" + conversationId);
        }
        return session;
    }

    private String buildEndpoint(String endpointUri, String model) {
        String base = (endpointUri == null || endpointUri.isBlank()) ? DEFAULT_ENDPOINT : endpointUri;
        if (base.contains("model=")) {
            return base;
        }
        if (base.contains("?")) {
            return base + "&model=" + model;
        }
        return base + "?model=" + model;
    }

    private record RealtimeSession(
        String conversationId,
        String endpointUri,
        String model,
        String apiKey,
        WebSocket socket,
        RealtimeWebSocketListener listener,
        RealtimeReconnectPolicy reconnectPolicy
    ) {
        boolean open() {
            return socket != null && !socket.isOutputClosed() && !socket.isInputClosed();
        }

        void incrementReconnectCount() {
            listener.reconnectCounter().incrementAndGet();
        }

        void copyReconnectFrom(RealtimeSession source) {
            listener.reconnectCounter().set(source.listener().reconnectCounter().get());
        }

        int reconnectCount() {
            return listener.reconnectCounter().get();
        }

        RealtimeSession withSocket(WebSocket webSocket) {
            return new RealtimeSession(conversationId, endpointUri, model, apiKey, webSocket, listener, reconnectPolicy);
        }
    }

    private static final class RealtimeWebSocketListener implements WebSocket.Listener {

        private final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private final AtomicReference<String> lastError = new AtomicReference<>("");
        private final AtomicInteger lastCloseCode = new AtomicInteger(0);
        private final AtomicReference<String> lastCloseReason = new AtomicReference<>("");
        private final AtomicReference<String> lastConnectedAt = new AtomicReference<>("");
        private final AtomicReference<String> lastDisconnectedAt = new AtomicReference<>("");
        private final AtomicInteger reconnectCounter = new AtomicInteger(0);
        private final Object sendLock = new Object();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
            markConnected();
            lastCloseCode.set(0);
            lastCloseReason.set("");
            LOGGER.debug("Realtime websocket opened");
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            current.append(Objects.toString(data, ""));
            if (last) {
                messages.add(current.toString());
                current.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            markClosed(statusCode, reason);
            messages.add("{\"type\":\"relay.closed\",\"statusCode\":" + statusCode + ",\"reason\":\"" + sanitize(reason) + "\"}");
            LOGGER.info("Realtime websocket closed: statusCode={}, reason={}", statusCode, reason == null ? "" : reason);
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (error != null && error.getMessage() != null) {
                markError(error.getMessage());
                messages.add("{\"type\":\"relay.error\",\"message\":\"" + sanitize(error.getMessage()) + "\"}");
                LOGGER.warn("Realtime websocket error: message={}", error.getMessage());
            }
            webSocket.request(1);
        }

        void markConnected() {
            lastConnectedAt.set(Instant.now().toString());
        }

        void markClosed(int statusCode, String reason) {
            lastCloseCode.set(statusCode);
            lastCloseReason.set(reason == null ? "" : reason);
            lastDisconnectedAt.set(Instant.now().toString());
        }

        void markError(String message) {
            lastError.set(message == null ? "unknown relay error" : message);
        }

        void clearError() {
            lastError.set("");
        }

        String lastError() {
            return lastError.get();
        }

        int lastCloseCode() {
            return lastCloseCode.get();
        }

        String lastCloseReason() {
            return lastCloseReason.get();
        }

        String lastConnectedAt() {
            return lastConnectedAt.get();
        }

        String lastDisconnectedAt() {
            return lastDisconnectedAt.get();
        }

        AtomicInteger reconnectCounter() {
            return reconnectCounter;
        }

        Object sendLock() {
            return sendLock;
        }

        List<String> drain() {
            List<String> copy = List.copyOf(messages);
            messages.clear();
            return copy;
        }

        private String sanitize(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("\\", "\\\\").replace("\"", "'");
        }
    }
}
