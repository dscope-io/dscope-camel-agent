package io.dscope.camel.agent.realtime.sip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public final class SipCallEndProcessor implements Processor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void process(Exchange exchange) throws Exception {
        String conversationId = exchange.getMessage().getHeader("conversationId", String.class);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("conversationId", conversationId == null ? "" : conversationId);
        body.put("ended", true);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(body));
    }
}
