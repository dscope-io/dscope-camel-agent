package io.dscope.camel.agent.runtime;

import org.apache.camel.builder.RouteBuilder;

public class AgentRuntimeRouteBuilder extends RouteBuilder {

    private final AgentRuntimeProperties properties;

    public AgentRuntimeRouteBuilder(AgentRuntimeProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configure() {
        from("timer:agent-demo?repeatCount=1")
            .routeId("sample-agent-invoke")
            .setHeader("agent.conversationId", simple("sample-${exchangeId}"))
            .setBody(constant(properties.initialPrompt()))
            .toD("agent:support?blueprint=" + properties.blueprint())
            .log("Agent response: ${body}")
            .setHeader("conversationId", header("agent.conversationId"))
            .setHeader("limit", constant(String.valueOf(properties.auditTrailLimit())))
            .bean("auditTrailService", "loadTrail(${header.conversationId},${header.limit})")
            .log("Audit trail for ${header.conversationId}: ${body}");

        if (properties.auditApiEnabled()) {
            from("undertow:http://" + properties.auditApiHost() + ":" + properties.auditApiPort() + "/audit/{conversationId}?httpMethodRestrict=GET")
                .routeId("audit-trail-api")
                .setHeader("conversationId", header("conversationId"))
                .setHeader("limit", header("limit"))
                .bean("auditTrailService", "loadTrail(${header.conversationId},${header.limit})")
                .setHeader("Content-Type", constant("application/json"));
        }
    }
}
