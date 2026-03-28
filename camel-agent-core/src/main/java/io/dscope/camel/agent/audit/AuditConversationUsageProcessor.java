package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

public class AuditConversationUsageProcessor implements Processor {

    private static final int DEFAULT_LIMIT = 2000;

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;

    public AuditConversationUsageProcessor(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
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

        List<AgentEvent> events = persistenceFacade.loadConversation(conversationId, parseLimit(readText(in, "limit")));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("eventCount", events.size());
        response.put("modelUsage", AuditUsageSupport.summarize(events));

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
            return Math.max(1, Math.min(Integer.parseInt(limitText.trim()), DEFAULT_LIMIT));
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