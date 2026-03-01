package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.config.CorrelationKeys;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

public class AuditConversationAgentMessageProcessor implements Processor {

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;

    public AuditConversationAgentMessageProcessor(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Message in = exchange.getIn();
        JsonNode body = parseBody(in.getBody(String.class));

        String conversationId = firstNonBlank(
            readText(in, "conversationId"),
            text(body, "conversationId")
        );
        String message = firstNonBlank(
            readText(in, "message"),
            readText(in, "text"),
            text(body, "message"),
            text(body, "text")
        );

        if (conversationId.isBlank()) {
            writeBadRequest(exchange, "conversationId is required");
            return;
        }
        if (message.isBlank()) {
            writeBadRequest(exchange, "message (or text) is required");
            return;
        }

        String aguiSessionId = firstNonBlank(
            readText(in, "sessionId"),
            text(body, "sessionId"),
            CorrelationRegistry.global().resolve(conversationId, CorrelationKeys.AGUI_SESSION_ID, "")
        );
        String aguiRunId = firstNonBlank(
            readText(in, "runId"),
            text(body, "runId"),
            CorrelationRegistry.global().resolve(conversationId, CorrelationKeys.AGUI_RUN_ID, "")
        );
        String aguiThreadId = firstNonBlank(
            readText(in, "threadId"),
            text(body, "threadId"),
            CorrelationRegistry.global().resolve(conversationId, CorrelationKeys.AGUI_THREAD_ID, "")
        );

        if (!aguiSessionId.isBlank()) {
            CorrelationRegistry.global().bind(conversationId, CorrelationKeys.AGUI_SESSION_ID, aguiSessionId);
        }
        if (!aguiRunId.isBlank()) {
            CorrelationRegistry.global().bind(conversationId, CorrelationKeys.AGUI_RUN_ID, aguiRunId);
        }
        if (!aguiThreadId.isBlank()) {
            CorrelationRegistry.global().bind(conversationId, CorrelationKeys.AGUI_THREAD_ID, aguiThreadId);
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("role", "assistant");
        payload.put("text", message);
        payload.put("message", message);
        payload.put("source", "audit.console");
        payload.put("manual", true);
        ObjectNode correlation = payload.putObject("correlation");
        if (!aguiSessionId.isBlank()) {
            correlation.put("aguiSessionId", aguiSessionId);
        }
        if (!aguiRunId.isBlank()) {
            correlation.put("aguiRunId", aguiRunId);
        }
        if (!aguiThreadId.isBlank()) {
            correlation.put("aguiThreadId", aguiThreadId);
        }

        Instant now = Instant.now();
        persistenceFacade.appendEvent(
            new AgentEvent(conversationId, null, "assistant.manual.message", payload, now),
            UUID.randomUUID().toString()
        );

        ObjectNode response = objectMapper.createObjectNode();
        response.put("accepted", true);
        response.put("conversationId", conversationId);
        response.put("message", message);
        response.put("timestamp", now.toString());
        response.put("aguiSessionId", aguiSessionId);
        response.put("aguiRunId", aguiRunId);
        response.put("aguiThreadId", aguiThreadId);

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private JsonNode parseBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            return parsed == null ? objectMapper.createObjectNode() : parsed;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode node, String name) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return nonBlank(node.path(name).asText(""));
    }

    private static String readText(Message in, String headerName) {
        Object value = in.getHeader(headerName);
        if (value == null) {
            return "";
        }
        return nonBlank(String.valueOf(value));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private void writeBadRequest(Exchange exchange, String message) throws Exception {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(Map.of(
            "error", "bad_request",
            "message", message
        )));
    }
}
