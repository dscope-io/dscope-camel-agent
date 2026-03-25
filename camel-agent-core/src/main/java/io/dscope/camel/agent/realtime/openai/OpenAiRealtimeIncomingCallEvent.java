package io.dscope.camel.agent.realtime.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.dscope.camel.agent.telephony.sip.SipHeader;
import java.util.ArrayList;
import java.util.List;

public record OpenAiRealtimeIncomingCallEvent(
    String callId,
    List<SipHeader> sipHeaders,
    JsonNode rawData
) {

    public static OpenAiRealtimeIncomingCallEvent fromEvent(JsonNode event) {
        JsonNode data = event == null ? null : event.path("data");
        String callId = textAt(data, "call_id");
        List<SipHeader> headers = new ArrayList<>();
        JsonNode headerNodes = data == null ? null : data.path("sip_headers");
        if (headerNodes != null && headerNodes.isArray()) {
            for (JsonNode headerNode : headerNodes) {
                String name = textAt(headerNode, "name");
                String value = textAt(headerNode, "value");
                if (name != null && !name.isBlank()) {
                    headers.add(new SipHeader(name, value));
                }
            }
        }
        return new OpenAiRealtimeIncomingCallEvent(callId, List.copyOf(headers), data);
    }

    private static String textAt(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }
}