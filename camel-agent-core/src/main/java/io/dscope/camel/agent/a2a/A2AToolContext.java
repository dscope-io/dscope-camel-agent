package io.dscope.camel.agent.a2a;

public record A2AToolContext(
    String planName,
    String planVersion,
    String agentName,
    String agentVersion
) {
    public static final A2AToolContext EMPTY = new A2AToolContext("", "", "", "");
}
