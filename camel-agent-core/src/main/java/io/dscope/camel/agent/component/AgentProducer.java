package io.dscope.camel.agent.component;

import io.dscope.camel.agent.api.AgentKernel;
import io.dscope.camel.agent.model.AgentResponse;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;

public class AgentProducer extends DefaultProducer {

    private final AgentKernel agentKernel;

    public AgentProducer(AgentEndpoint endpoint, AgentKernel agentKernel) {
        super(endpoint);
        this.agentKernel = agentKernel;
    }

    @Override
    public void process(Exchange exchange) {
        String conversationId = exchange.getMessage().getHeader("agent.conversationId", String.class);
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        String body = exchange.getMessage().getBody(String.class);
        AgentResponse response = agentKernel.handleUserMessage(conversationId, body == null ? "" : body);
        exchange.getMessage().setHeader("agent.conversationId", response.conversationId());
        exchange.getMessage().setBody(response.message());
    }
}
