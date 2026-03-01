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

public class AuditAgentBlueprintProcessor implements Processor {

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;
    private final String blueprintUri;

    public AuditAgentBlueprintProcessor(PersistenceFacade persistenceFacade, ObjectMapper objectMapper, String blueprintUri) {
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
        this.blueprintUri = blueprintUri == null || blueprintUri.isBlank() ? null : blueprintUri;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Message in = exchange.getIn();
        String conversationId = headerText(in, "conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            writeError(exchange, 400, "bad_request", "conversationId query parameter is required");
            return;
        }

        List<AgentEvent> events = persistenceFacade.loadConversation(conversationId, 300);
        boolean conversationFound = !events.isEmpty();
        String content = AuditMetadataSupport.loadBlueprintContent(blueprintUri);
        if (content == null) {
            writeError(exchange, 404, "not_found", "Agent blueprint file not found: " + blueprintUri);
            return;
        }

        AuditMetadataSupport.BlueprintMetadata blueprintMetadata = AuditMetadataSupport.parseBlueprintMetadata(content);
        Map<String, Object> conversationMetadata = AuditMetadataSupport.buildConversationMetadata(conversationId, events, blueprintMetadata);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("conversationFound", conversationFound);
        response.put("blueprintUri", blueprintUri);
        response.put("metadata", conversationMetadata);
        response.put("content", content);

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private static String headerText(Message in, String name) {
        Object value = in.getHeader(name);
        return value == null ? null : String.valueOf(value);
    }

    private void writeError(Exchange exchange, int code, String error, String message) throws Exception {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, code);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(Map.of(
            "error", error,
            "message", message
        )));
    }
}