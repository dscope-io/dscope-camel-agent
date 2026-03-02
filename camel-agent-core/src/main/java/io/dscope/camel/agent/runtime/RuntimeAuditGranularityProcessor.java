package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.model.AuditGranularity;
import java.io.IOException;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class RuntimeAuditGranularityProcessor implements Processor {

    private final ObjectMapper objectMapper;
    private final RuntimeControlState runtimeControlState;

    public RuntimeAuditGranularityProcessor(ObjectMapper objectMapper, RuntimeControlState runtimeControlState) {
        this.objectMapper = objectMapper;
        this.runtimeControlState = runtimeControlState;
    }

    @Override
    public void process(Exchange exchange) {
        String requested = firstNonBlank(
            exchange.getMessage().getHeader("granularity", String.class),
            exchange.getMessage().getHeader("auditGranularity", String.class),
            bodyGranularity(exchange)
        );

        boolean updated = false;
        if (!requested.isBlank()) {
            runtimeControlState.setAuditGranularity(AuditGranularity.from(requested));
            updated = true;
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("updated", updated);
        body.put("auditGranularity", runtimeControlState.auditGranularity().name().toLowerCase());
        body.put("requested", requested);

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(body.toString());
    }

    private String bodyGranularity(Exchange exchange) {
        try {
            String raw = exchange.getMessage().getBody(String.class);
            if (raw == null || raw.isBlank()) {
                return "";
            }
            JsonNode root = objectMapper.readTree(raw);
            return firstNonBlank(text(root, "granularity"), text(root, "auditGranularity"));
        } catch (IOException ignored) {
            return "";
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
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
}
