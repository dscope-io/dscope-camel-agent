package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.time.Instant;
import java.util.UUID;
import org.apache.camel.Exchange;

final class SupportSipAuditSupport {

    private SupportSipAuditSupport() {
    }

    static void appendEvent(Exchange exchange, String conversationId, String type, ObjectNode payload) {
        if (exchange == null || conversationId == null || conversationId.isBlank() || type == null || type.isBlank()) {
            return;
        }
        PersistenceFacade persistenceFacade = exchange.getContext().getRegistry().lookupByNameAndType("persistenceFacade", PersistenceFacade.class);
        if (persistenceFacade == null) {
            return;
        }
        ObjectMapper objectMapper = exchange.getContext().getRegistry().lookupByNameAndType("objectMapper", ObjectMapper.class);
        ObjectNode body = payload == null
            ? (objectMapper == null ? new ObjectMapper().createObjectNode() : objectMapper.createObjectNode())
            : payload.deepCopy();
        if (!body.hasNonNull("channel")) {
            body.put("channel", "sip");
        }
        persistenceFacade.appendEvent(
            new AgentEvent(conversationId.trim(), null, type.trim(), body, Instant.now()),
            "sip-event-" + UUID.randomUUID()
        );
    }
}