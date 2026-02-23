package io.dscope.camel.agent.component;

import io.dscope.camel.agent.api.AgentKernel;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.config.CorrelationKeys;
import io.dscope.camel.agent.model.AgentResponse;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;

public class AgentProducer extends DefaultProducer {

    private final AgentKernel agentKernel;
    private final CorrelationRegistry correlationRegistry;

    public AgentProducer(AgentEndpoint endpoint, AgentKernel agentKernel, CorrelationRegistry correlationRegistry) {
        super(endpoint);
        this.agentKernel = agentKernel;
        this.correlationRegistry = correlationRegistry;
    }

    @Override
    public void process(Exchange exchange) {
        String conversationId = exchange.getMessage().getHeader("agent.conversationId", String.class);
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        registerCorrelation(conversationId, exchange);
        String body = exchange.getMessage().getBody(String.class);
        AgentResponse response = agentKernel.handleUserMessage(conversationId, body == null ? "" : body);
        exchange.getMessage().setHeader("agent.conversationId", response.conversationId());
        exchange.getMessage().setBody(response.message());
    }

    private void registerCorrelation(String conversationId, Exchange exchange) {
        if (correlationRegistry == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        String aguiSessionId = exchange.getMessage().getHeader(AgentHeaders.AGUI_SESSION_ID, String.class);
        String aguiRunId = exchange.getMessage().getHeader(AgentHeaders.AGUI_RUN_ID, String.class);
        String aguiThreadId = exchange.getMessage().getHeader(AgentHeaders.AGUI_THREAD_ID, String.class);

        if (aguiSessionId != null && !aguiSessionId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.AGUI_SESSION_ID, aguiSessionId);
        }
        if (aguiRunId != null && !aguiRunId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.AGUI_RUN_ID, aguiRunId);
        }
        if (aguiThreadId != null && !aguiThreadId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.AGUI_THREAD_ID, aguiThreadId);
        }
    }
}
