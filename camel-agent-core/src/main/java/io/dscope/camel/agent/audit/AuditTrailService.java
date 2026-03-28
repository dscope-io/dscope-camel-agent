package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AuditTrailService {

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;

    public AuditTrailService(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
    }

    public String loadTrail(String conversationId, String limitText) throws Exception {
        if (conversationId == null || conversationId.isBlank()) {
            return "[]";
        }
        int limit = parseLimit(limitText);
        List<AgentEvent> events = persistenceFacade.loadConversation(conversationId, limit);
        return objectMapper.writeValueAsString(events.stream().map(this::toJson).toList());
    }

    public String loadUsage(String conversationId, String limitText) throws Exception {
        if (conversationId == null || conversationId.isBlank()) {
            return objectMapper.writeValueAsString(Map.of(
                "conversationId", "",
                "eventCount", 0,
                "modelUsage", AuditUsageSupport.summarize(List.of())
            ));
        }
        int limit = parseLimit(limitText);
        List<AgentEvent> events = persistenceFacade.loadConversation(conversationId, limit);
        return objectMapper.writeValueAsString(Map.of(
            "conversationId", conversationId,
            "eventCount", events.size(),
            "modelUsage", AuditUsageSupport.summarize(events)
        ));
    }

    private int parseLimit(String limitText) {
        if (limitText == null || limitText.isBlank()) {
            return 200;
        }
        try {
            return Math.max(1, Integer.parseInt(limitText));
        } catch (NumberFormatException ignored) {
            return 200;
        }
    }

    private Map<String, Object> toJson(AgentEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", event.conversationId());
        data.put("taskId", event.taskId());
        data.put("type", event.type());
        data.put("payload", event.payload());
        data.put("timestamp", event.timestamp() == null ? null : event.timestamp().toString());
        return data;
    }
}
