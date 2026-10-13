package io.dscope.camel.agent.realtime.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class OpenAiRealtimeCallControlRequestFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ObjectNode acceptRequest(String model, String instructions, String voice, JsonNode tools, ObjectNode session) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("type", "realtime");
        putIfText(request, "model", model);
        putIfText(request, "instructions", instructions);
        if (tools != null && !tools.isNull()) {
            request.set("tools", tools.deepCopy());
        }
        if (session != null && !session.isEmpty()) {
            request.setAll(session.deepCopy());
        }
        String resolvedVoice = text(request.path("audio").path("output"), "voice");
        if (resolvedVoice.isBlank()) {
            resolvedVoice = firstNonBlank(text(request, "voice"), voice);
        }
        request.remove("voice");
        if (!resolvedVoice.isBlank()) {
            ObjectNode audio = request.withObject("audio");
            audio.withObject("output").put("voice", resolvedVoice);
        }
        return request;
    }

    public ObjectNode rejectRequest(Integer statusCode) {
        ObjectNode request = MAPPER.createObjectNode();
        if (statusCode != null) {
            request.put("status_code", statusCode.intValue());
        }
        return request;
    }

    public ObjectNode referRequest(String targetUri) {
        ObjectNode request = MAPPER.createObjectNode();
        putIfText(request, "target_uri", targetUri);
        return request;
    }

    public ObjectNode hangupRequest() {
        return MAPPER.createObjectNode();
    }

    private static void putIfText(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}