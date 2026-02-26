package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.realtime.RealtimeBrowserSessionRegistry;
import io.dscope.camel.agent.realtime.RealtimeRelayClient;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class RuntimeConversationCloseProcessor implements Processor {

    private final ObjectMapper objectMapper;
    private final PersistenceFacade persistenceFacade;

    public RuntimeConversationCloseProcessor(ObjectMapper objectMapper, PersistenceFacade persistenceFacade) {
        this.objectMapper = objectMapper;
        this.persistenceFacade = persistenceFacade;
    }

    @Override
    public void process(Exchange exchange) {
        String conversationId = requestedConversationId(exchange);
        if (conversationId.isBlank()) {
            writeError(exchange, 400, "conversationId is required");
            return;
        }

        try {
            int chatMemoryCleared = clearChatMemory(exchange, conversationId);
            int realtimeSessionCleared = clearRealtimeSessionRegistry(exchange, conversationId);
            int realtimeRelayClosed = closeRealtimeRelaySession(exchange, conversationId);
            int closeEventPersisted = persistCloseEvent(conversationId, chatMemoryCleared, realtimeSessionCleared, realtimeRelayClosed);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("closed", true);
            body.put("conversationId", conversationId);
            body.put("chatMemoryCleared", chatMemoryCleared);
            body.put("realtimeSessionCleared", realtimeSessionCleared);
            body.put("realtimeRelayClosed", realtimeRelayClosed);
            body.put("closeEventPersisted", closeEventPersisted);
            body.put("jdbcAuditDataPreserved", true);

            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setHeader("Content-Type", "application/json");
            exchange.getMessage().setBody(body.toString());
        } catch (Exception closeFailure) {
            writeError(exchange, 500, closeFailure.getMessage() == null
                ? closeFailure.getClass().getSimpleName()
                : closeFailure.getMessage());
        }
    }

    private int clearChatMemory(Exchange exchange, String conversationId) {
        Object repository = exchange.getContext().getRegistry().lookupByName("chatMemoryRepository");
        if (repository == null) {
            return 0;
        }

        try {
            repository.getClass().getMethod("deleteByConversationId", String.class).invoke(repository, conversationId);
            return 1;
        } catch (ReflectiveOperationException ignored) {
            return 0;
        }
    }

    private int clearRealtimeSessionRegistry(Exchange exchange, String conversationId) {
        RealtimeBrowserSessionRegistry registry = exchange.getContext().getRegistry()
            .lookupByNameAndType("supportRealtimeSessionRegistry", RealtimeBrowserSessionRegistry.class);
        if (registry == null) {
            return 0;
        }

        boolean existed = registry.getSession(conversationId) != null;
        registry.removeSession(conversationId);
        return existed ? 1 : 0;
    }

    private int closeRealtimeRelaySession(Exchange exchange, String conversationId) {
        RealtimeRelayClient relayClient = exchange.getContext().getRegistry().findSingleByType(RealtimeRelayClient.class);
        if (relayClient == null) {
            return 0;
        }

        boolean wasConnected;
        try {
            wasConnected = relayClient.isConnected(conversationId);
        } catch (Exception ignored) {
            wasConnected = false;
        }
        relayClient.close(conversationId);
        return wasConnected ? 1 : 0;
    }

    private int persistCloseEvent(String conversationId,
                                  int chatMemoryCleared,
                                  int realtimeSessionCleared,
                                  int realtimeRelayClosed) {
        if (persistenceFacade == null) {
            return 0;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("closedAt", Instant.now().toString());
        payload.put("chatMemoryCleared", chatMemoryCleared > 0);
        payload.put("realtimeSessionCleared", realtimeSessionCleared > 0);
        payload.put("realtimeRelayClosed", realtimeRelayClosed > 0);
        payload.put("reason", "admin.close.conversation.instance");

        persistenceFacade.appendEvent(
            new AgentEvent(conversationId, null, "agent.instance.closed", payload, Instant.now()),
            UUID.randomUUID().toString()
        );
        return 1;
    }

    private String requestedConversationId(Exchange exchange) {
        return firstNonBlank(
            exchange.getMessage().getHeader("conversationId", String.class),
            exchange.getMessage().getHeader("conversation-id", String.class),
            bodyConversationId(exchange)
        );
    }

    private String bodyConversationId(Exchange exchange) {
        try {
            String raw = exchange.getMessage().getBody(String.class);
            if (raw == null || raw.isBlank()) {
                return "";
            }
            JsonNode root = objectMapper.readTree(raw);
            String id = text(root, "conversationId");
            if (id.isBlank()) {
                id = text(root, "conversation-id");
            }
            return id;
        } catch (IOException ignored) {
            return "";
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

    private void writeError(Exchange exchange, int statusCode, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("closed", false);
        error.put("error", message == null ? "unknown error" : message);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode);
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(error.toString());
    }
}
