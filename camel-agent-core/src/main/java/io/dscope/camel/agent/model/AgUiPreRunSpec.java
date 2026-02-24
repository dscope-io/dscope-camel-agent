package io.dscope.camel.agent.model;

import java.util.List;

public record AgUiPreRunSpec(
    String agentEndpointUri,
    Boolean fallbackEnabled,
    String kbToolName,
    String ticketToolName,
    String kbUri,
    String ticketUri,
    List<String> ticketKeywords,
    List<String> fallbackErrorMarkers
) {
}
