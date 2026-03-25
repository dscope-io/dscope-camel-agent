package io.dscope.camel.agent.session;

import io.dscope.camel.agent.api.AgentKernel;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.component.AgentComponent;
import io.dscope.camel.agent.component.AgentEndpoint;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.config.CorrelationKeys;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.AgentResponse;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.agent.runtime.ResolvedAgentPlan;
import io.dscope.camel.agent.runtime.RuntimePlaceholderResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.Exchange;

public class AgentSessionService {

    public static final String DEFAULT_AGENT_ENDPOINT_URI = "agent:default?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}";
    public static final String SESSION_PARAMS_PROPERTY = "agent.session.params";
    public static final String SESSION_RESPONSE_PROPERTY = "agent.session.response";
    public static final String SESSION_CREATED_PROPERTY = "agent.session.created";

    public AgentSessionResponse invoke(Exchange exchange, AgentSessionRequest request) {
        return invoke(exchange, request, resolveAgentEndpointUri(exchange));
    }

    public AgentSessionResponse invoke(Exchange exchange, AgentSessionRequest request, String agentEndpointUri) {
        if (exchange == null || exchange.getContext() == null) {
            throw new IllegalArgumentException("Exchange with CamelContext is required");
        }

        AgentSessionRequest effectiveRequest = request == null
            ? new AgentSessionRequest("", "", "", "", "", "", Map.of())
            : request;
        AgentEndpoint endpoint = exchange.getContext().getEndpoint(firstNonBlank(agentEndpointUri, DEFAULT_AGENT_ENDPOINT_URI), AgentEndpoint.class);
        if (endpoint == null) {
            throw new IllegalArgumentException("Agent endpoint not found: " + agentEndpointUri);
        }

        AgentComponent component = (AgentComponent) endpoint.getComponent();
        String requestedConversationId = requestedConversationId(exchange, effectiveRequest);
        boolean created = requestedConversationId.isBlank();
        String conversationId = created ? UUID.randomUUID().toString() : requestedConversationId;
        String prompt = firstNonBlank(effectiveRequest.prompt(), exchange.getMessage().getBody(String.class), "");

        applyRequestMetadata(exchange, effectiveRequest, conversationId);
        registerCorrelation(endpoint.getCorrelationRegistry(), conversationId, effectiveRequest, exchange);

        ResolvedAgentPlan resolvedPlan = component.resolvePlan(endpoint, exchange, conversationId);
        applyResolvedHeaders(exchange, resolvedPlan);
        persistSelectionIfNeeded(component.planResolver(), component.persistenceFacade(), conversationId, resolvedPlan);

        AgentKernel agentKernel = component.resolveKernel(endpoint, resolvedPlan);
        AgentResponse response = agentKernel.handleUserMessage(conversationId, prompt);
        exchange.setProperty(AgentHeaders.RESPONSE_TURN_EVENTS_PERSISTED, hasTurnEvents(response.events()));
        exchange.setProperty(SESSION_CREATED_PROPERTY, created);
        exchange.getMessage().setHeader(AgentHeaders.CONVERSATION_ID, response.conversationId());

        AgentSessionResponse sessionResponse = new AgentSessionResponse(
            response.conversationId(),
            response.conversationId(),
            created,
            response.message(),
            response.events(),
            response.taskState(),
            resolvedPlan == null || resolvedPlan.legacyMode() ? "" : defaultIfBlank(resolvedPlan.planName(), ""),
            resolvedPlan == null || resolvedPlan.legacyMode() ? "" : defaultIfBlank(resolvedPlan.planVersion(), ""),
            resolvedPlan == null ? "" : defaultIfBlank(resolvedPlan.blueprint(), ""),
            effectiveRequest.params()
        );
        exchange.setProperty(SESSION_RESPONSE_PROPERTY, sessionResponse);
        return sessionResponse;
    }

    public String resolveAgentEndpointUri(Exchange exchange) {
        return RuntimePlaceholderResolver.resolveRequiredExecutionTarget(exchange == null ? null : exchange.getContext(), firstNonBlank(
            exchange == null ? null : exchange.getMessage().getHeader("agent.session.endpointUri", String.class),
            exchange == null ? null : exchange.getMessage().getHeader("agent.session.agentEndpointUri", String.class),
            exchange == null ? null : exchange.getMessage().getHeader("agent.endpointUri", String.class),
            exchange == null ? null : exchange.getMessage().getHeader("agent.agentEndpointUri", String.class),
            exchange == null ? null : exchange.getProperty("agent.session.endpointUri", String.class),
            exchange == null ? null : exchange.getProperty("agent.session.agentEndpointUri", String.class),
            exchange == null ? null : exchange.getProperty("agent.endpointUri", String.class),
            exchange == null ? null : exchange.getProperty("agent.agentEndpointUri", String.class),
            DEFAULT_AGENT_ENDPOINT_URI
        ), "agent.session.endpointUri");
    }

    private void applyRequestMetadata(Exchange exchange, AgentSessionRequest request, String conversationId) {
        exchange.getMessage().setHeader(AgentHeaders.CONVERSATION_ID, conversationId);
        if (!isBlank(request.planName())) {
            exchange.getMessage().setHeader(AgentHeaders.PLAN_NAME, request.planName());
        }
        if (!isBlank(request.planVersion())) {
            exchange.getMessage().setHeader(AgentHeaders.PLAN_VERSION, request.planVersion());
        }
        if (!isBlank(request.sessionId())) {
            exchange.getMessage().setHeader(AgentHeaders.AGUI_SESSION_ID, request.sessionId());
        }
        if (!isBlank(request.threadId())) {
            exchange.getMessage().setHeader(AgentHeaders.AGUI_THREAD_ID, request.threadId());
        }
        exchange.setProperty(SESSION_PARAMS_PROPERTY, request.params());
        if (request.params() != null) {
            request.params().forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    exchange.setProperty(SESSION_PARAMS_PROPERTY + "." + key, value);
                }
            });
        }
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
                                          PersistenceFacade persistenceFacade,
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

    private void registerCorrelation(CorrelationRegistry correlationRegistry,
                                     String conversationId,
                                     AgentSessionRequest request,
                                     Exchange exchange) {
        if (correlationRegistry == null || isBlank(conversationId)) {
            return;
        }
        bindCorrelation(correlationRegistry, conversationId, CorrelationKeys.AGUI_SESSION_ID,
            firstNonBlank(request.sessionId(), exchange.getMessage().getHeader(AgentHeaders.AGUI_SESSION_ID, String.class)));
        bindCorrelation(correlationRegistry, conversationId, CorrelationKeys.AGUI_THREAD_ID,
            firstNonBlank(request.threadId(), exchange.getMessage().getHeader(AgentHeaders.AGUI_THREAD_ID, String.class)));
        bindCorrelation(correlationRegistry, conversationId, CorrelationKeys.AGUI_RUN_ID,
            exchange.getMessage().getHeader(AgentHeaders.AGUI_RUN_ID, String.class));
    }

    private void bindCorrelation(CorrelationRegistry registry, String conversationId, String key, String value) {
        if (!isBlank(value)) {
            registry.bind(conversationId, key, value);
        }
    }

    private String requestedConversationId(Exchange exchange, AgentSessionRequest request) {
        return firstNonBlank(
            request.conversationId(),
            request.sessionId(),
            request.threadId(),
            exchange.getMessage().getHeader(AgentHeaders.CONVERSATION_ID, String.class),
            exchange.getMessage().getHeader(AgentHeaders.AGUI_SESSION_ID, String.class),
            exchange.getMessage().getHeader(AgentHeaders.AGUI_THREAD_ID, String.class),
            exchange.getMessage().getHeader("conversationId", String.class),
            exchange.getMessage().getHeader("sessionId", String.class),
            exchange.getMessage().getHeader("threadId", String.class)
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

    private String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }
}