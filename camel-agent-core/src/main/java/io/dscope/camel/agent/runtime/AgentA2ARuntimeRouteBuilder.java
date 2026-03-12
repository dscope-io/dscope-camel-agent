package io.dscope.camel.agent.runtime;

import io.dscope.camel.a2a.A2AComponentApplicationSupport;
import org.apache.camel.builder.RouteBuilder;

public class AgentA2ARuntimeRouteBuilder extends RouteBuilder {

    private final A2ARuntimeProperties properties;

    public AgentA2ARuntimeRouteBuilder(A2ARuntimeProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configure() {
        onException(Exception.class)
            .handled(true)
            .to("bean:" + A2AComponentApplicationSupport.BEAN_ERROR_PROCESSOR);

        from(undertowUri(properties.rpcPath(), "POST"))
            .routeId("agent-a2a-rpc")
            .convertBodyTo(String.class)
            .to("bean:" + A2AComponentApplicationSupport.BEAN_ENVELOPE_PROCESSOR)
            .to("bean:" + A2AComponentApplicationSupport.BEAN_METHOD_PROCESSOR);

        from(undertowUri(properties.ssePath() + "/{taskId}", "GET"))
            .routeId("agent-a2a-sse")
            .to("bean:" + A2AComponentApplicationSupport.BEAN_SSE_PROCESSOR);

        from(undertowUri(properties.agentCardPath(), "GET"))
            .routeId("agent-a2a-agent-card")
            .to("bean:" + A2AComponentApplicationSupport.BEAN_AGENT_CARD_DISCOVERY_PROCESSOR);
    }

    private String undertowUri(String path, String method) {
        return "undertow:http://"
            + properties.host()
            + ":"
            + properties.port()
            + path
            + "?httpMethodRestrict="
            + method;
    }
}
