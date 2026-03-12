package io.dscope.camel.agent.component;

import io.dscope.camel.agent.api.AgentKernel;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.config.CorrelationKeys;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.AgentResponse;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.agent.runtime.ResolvedAgentPlan;
import java.util.List;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;

public class AgentProducer extends DefaultProducer {

    private final AgentEndpoint endpoint;
    private final CorrelationRegistry correlationRegistry;

    public AgentProducer(AgentEndpoint endpoint, AgentKernel agentKernel, CorrelationRegistry correlationRegistry) {
        super(endpoint);
        this.endpoint = endpoint;
        this.correlationRegistry = correlationRegistry;
    }

    @Override
    public void process(Exchange exchange) {
        String conversationId = exchange.getMessage().getHeader(AgentHeaders.CONVERSATION_ID, String.class);
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        registerCorrelation(conversationId, exchange);
        AgentComponent component = (AgentComponent) endpoint.getComponent();
        ResolvedAgentPlan resolvedPlan = component.resolvePlan(endpoint, exchange, conversationId);
        applyResolvedHeaders(exchange, resolvedPlan);
        persistSelectionIfNeeded(component.planResolver(), component.persistenceFacade(), conversationId, resolvedPlan);
        AgentKernel agentKernel = component.resolveKernel(endpoint, resolvedPlan);
        String body = exchange.getMessage().getBody(String.class);
        AgentResponse response = agentKernel.handleUserMessage(conversationId, body == null ? "" : body);
        exchange.setProperty(AgentHeaders.RESPONSE_TURN_EVENTS_PERSISTED, hasTurnEvents(response.events()));
        exchange.getMessage().setHeader(AgentHeaders.CONVERSATION_ID, response.conversationId());
        exchange.getMessage().setBody(response.message());
    }

    private void applyResolvedHeaders(Exchange exchange, ResolvedAgentPlan resolvedPlan) {
        if (resolvedPlan == null) {
            return;
        }
        if (!resolvedPlan.legacyMode()) {
            exchange.getMessage().setHeader(AgentHeaders.RESOLVED_PLAN_NAME, resolvedPlan.planName());
            exchange.getMessage().setHeader(AgentHeaders.RESOLVED_PLAN_VERSION, resolvedPlan.planVersion());
        }
        exchange.getMessage().setHeader(AgentHeaders.RESOLVED_BLUEPRINT, resolvedPlan.blueprint());
    }

    private void persistSelectionIfNeeded(AgentPlanSelectionResolver resolver,
                                          io.dscope.camel.agent.api.PersistenceFacade persistenceFacade,
                                          String conversationId,
                                          ResolvedAgentPlan resolvedPlan) {
        if (resolver == null || persistenceFacade == null || resolvedPlan == null || resolvedPlan.legacyMode() || !resolvedPlan.selectionPersistRequired()) {
            return;
        }
        persistenceFacade.appendEvent(
            resolver.selectionEvent(conversationId, resolvedPlan),
            UUID.randomUUID().toString()
        );
    }

    private boolean hasTurnEvents(List<AgentEvent> events) {
        if (events == null || events.isEmpty()) {
            return false;
        }
        boolean hasUserMessage = false;
        boolean hasAgentMessage = false;
        for (AgentEvent event : events) {
            if (event == null || event.type() == null) {
                continue;
            }
            if ("user.message".equals(event.type())) {
                hasUserMessage = true;
            } else if ("agent.message".equals(event.type())) {
                hasAgentMessage = true;
            }
            if (hasUserMessage && hasAgentMessage) {
                return true;
            }
        }
        return false;
    }

    private void registerCorrelation(String conversationId, Exchange exchange) {
        if (correlationRegistry == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        String aguiSessionId = exchange.getMessage().getHeader(AgentHeaders.AGUI_SESSION_ID, String.class);
        String aguiRunId = exchange.getMessage().getHeader(AgentHeaders.AGUI_RUN_ID, String.class);
        String aguiThreadId = exchange.getMessage().getHeader(AgentHeaders.AGUI_THREAD_ID, String.class);
        String a2aAgentId = exchange.getMessage().getHeader(AgentHeaders.A2A_AGENT_ID, String.class);
        String a2aRemoteConversationId = exchange.getMessage().getHeader(AgentHeaders.A2A_REMOTE_CONVERSATION_ID, String.class);
        String a2aRemoteTaskId = exchange.getMessage().getHeader(AgentHeaders.A2A_REMOTE_TASK_ID, String.class);
        String a2aLinkedConversationId = exchange.getMessage().getHeader(AgentHeaders.A2A_LINKED_CONVERSATION_ID, String.class);
        String a2aParentConversationId = exchange.getMessage().getHeader(AgentHeaders.A2A_PARENT_CONVERSATION_ID, String.class);
        String a2aRootConversationId = exchange.getMessage().getHeader(AgentHeaders.A2A_ROOT_CONVERSATION_ID, String.class);

        if (aguiSessionId != null && !aguiSessionId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.AGUI_SESSION_ID, aguiSessionId);
        }
        if (aguiRunId != null && !aguiRunId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.AGUI_RUN_ID, aguiRunId);
        }
        if (aguiThreadId != null && !aguiThreadId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.AGUI_THREAD_ID, aguiThreadId);
        }
        if (a2aAgentId != null && !a2aAgentId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.A2A_AGENT_ID, a2aAgentId);
        }
        if (a2aRemoteConversationId != null && !a2aRemoteConversationId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.A2A_REMOTE_CONVERSATION_ID, a2aRemoteConversationId);
        }
        if (a2aRemoteTaskId != null && !a2aRemoteTaskId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.A2A_REMOTE_TASK_ID, a2aRemoteTaskId);
        }
        if (a2aLinkedConversationId != null && !a2aLinkedConversationId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.A2A_LINKED_CONVERSATION_ID, a2aLinkedConversationId);
        }
        if (a2aParentConversationId != null && !a2aParentConversationId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.A2A_PARENT_CONVERSATION_ID, a2aParentConversationId);
        }
        if (a2aRootConversationId != null && !a2aRootConversationId.isBlank()) {
            correlationRegistry.bind(conversationId, CorrelationKeys.A2A_ROOT_CONVERSATION_ID, a2aRootConversationId);
        }
    }
}
