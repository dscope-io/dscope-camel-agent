package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.config.AgentHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.CRC32;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

/**
 * Small stateful ticket lifecycle processor used by the sample app.
 * It keeps per-conversation ticket state so the A2A ticket service can
 * demonstrate open/update/close/status flows across turns.
 */
public class TicketLifecycleProcessor implements Processor {

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, TicketRecord> ticketsByConversationId = new ConcurrentHashMap<>();

    public TicketLifecycleProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Message in = exchange.getIn();
        String conversationId = readConversationId(in);
        String query = normalizeQuery(in.getBody());

        TicketAction action = determineAction(query);
        TicketRecord current = ticketsByConversationId.compute(conversationId, (key, existing) -> applyAction(key, existing, action, query));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("ticketId", current.ticketId());
        payload.put("status", current.status());
        payload.put("action", action.name());
        payload.put("summary", current.summary());
        payload.put("assignedQueue", current.assignedQueue());
        payload.put("message", messageFor(action));
        payload.put("notifyClient", action == TicketAction.UPDATE || action == TicketAction.CLOSE || action == TicketAction.STATUS);
        payload.put("requiresCustomerAction", action == TicketAction.UPDATE && query.toLowerCase(Locale.ROOT).contains("confirm"));
        payload.put("conversationId", conversationId);
        payload.put("updatedAt", Instant.now().toString());

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(payload));
    }

    private TicketRecord applyAction(String conversationId, TicketRecord existing, TicketAction action, String query) {
        TicketRecord current = existing != null ? existing : createRecord(conversationId, query);
        return switch (action) {
            case OPEN -> createRecord(conversationId, query);
            case UPDATE -> new TicketRecord(
                current.ticketId(),
                "IN_PROGRESS",
                firstNonBlank(query, current.summary()),
                current.assignedQueue()
            );
            case CLOSE -> new TicketRecord(
                current.ticketId(),
                "CLOSED",
                firstNonBlank(query, current.summary()),
                current.assignedQueue()
            );
            case STATUS -> current;
        };
    }

    private TicketRecord createRecord(String conversationId, String query) {
        return new TicketRecord(
            ticketIdFor(conversationId),
            "OPEN",
            firstNonBlank(query, "Customer requested support follow-up"),
            queueFor(query)
        );
    }

    private String ticketIdFor(String conversationId) {
        CRC32 crc = new CRC32();
        crc.update(conversationId.getBytes(StandardCharsets.UTF_8));
        return "TCK-" + Long.toUnsignedString(crc.getValue(), 36).toUpperCase(Locale.ROOT);
    }

    private String queueFor(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        if (normalized.contains("bill") || normalized.contains("refund") || normalized.contains("invoice") || normalized.contains("payment")) {
            return "BILLING-L2";
        }
        if (normalized.contains("security") || normalized.contains("fraud")) {
            return "SECURITY-L2";
        }
        return "L1-SUPPORT";
    }

    private TicketAction determineAction(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "close", "closed", "resolve", "resolved", "done with", "cancel ticket")) {
            return TicketAction.CLOSE;
        }
        if (containsAny(normalized, "status", "progress", "check ticket", "where is", "what is happening")) {
            return TicketAction.STATUS;
        }
        if (containsAny(normalized, "update", "add note", "change", "modify", "priority", "reopen")) {
            return TicketAction.UPDATE;
        }
        return TicketAction.OPEN;
    }

    private boolean containsAny(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String messageFor(TicketAction action) {
        return switch (action) {
            case OPEN -> "Support ticket created successfully";
            case UPDATE -> "Support ticket updated and routed back to the client conversation";
            case CLOSE -> "Support ticket closed and the client conversation has been updated";
            case STATUS -> "Support ticket status retrieved successfully";
        };
    }

    private String normalizeQuery(Object body) {
        if (body instanceof Map<?, ?> map) {
            Object query = map.get("query");
            return query == null ? "" : String.valueOf(query).trim();
        }
        return body == null ? "" : String.valueOf(body).trim();
    }

    private String readConversationId(Message in) {
        String conversationId = in.getHeader(AgentHeaders.CONVERSATION_ID, String.class);
        if (conversationId == null || conversationId.isBlank()) {
            return "sample-ticket-fallback";
        }
        return conversationId.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private enum TicketAction {
        OPEN,
        UPDATE,
        CLOSE,
        STATUS
    }

    private record TicketRecord(String ticketId, String status, String summary, String assignedQueue) {
    }
}
