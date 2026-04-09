package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

public class AuditConversationSipProcessor implements Processor {

    private static final int DEFAULT_LIMIT = 300;

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;

    public AuditConversationSipProcessor(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Message in = exchange.getIn();
        String conversationId = readText(in, "conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            writeBadRequest(exchange, "conversationId query parameter is required");
            return;
        }

        int limit = parseLimit(readText(in, "limit"));
        List<AgentEvent> loaded = persistenceFacade.loadConversation(conversationId, limit);
        AuditMetadataSupport.SipMetadata sipMetadata = AuditMetadataSupport.deriveSipMetadata(loaded);
        List<Map<String, Object>> sipEvents = new ArrayList<>();

        AuditMetadataSupport.SipMetadata current = AuditMetadataSupport.SipMetadata.empty();
        for (AgentEvent event : loaded) {
            current = AuditMetadataSupport.advanceSipMetadata(current, event);
            if (event == null || event.type() == null || !event.type().startsWith("sip.")) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", event.type());
            row.put("timestamp", event.timestamp() == null ? "" : event.timestamp().toString());
            row.put("payload", event.payload());
            row.put("sip", current.asMap());
            sipEvents.add(row);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("present", sipMetadata.present());
        response.put("sip", sipMetadata.asMap());
        response.put("eventCount", sipEvents.size());
        response.put("events", sipEvents);

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
            return Math.max(1, Math.min(parsed, 2000));
        } catch (NumberFormatException ignored) {
            return DEFAULT_LIMIT;
        }
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