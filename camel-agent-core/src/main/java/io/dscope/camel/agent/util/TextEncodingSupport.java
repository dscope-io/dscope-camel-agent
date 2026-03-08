package io.dscope.camel.agent.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

public final class TextEncodingSupport {

    private TextEncodingSupport() {
    }

    public static String repairUtf8Mojibake(String value) {
        if (value == null || value.isBlank() || !looksLikeUtf8Mojibake(value)) {
            return value;
        }

        String candidate = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return mojibakeScore(candidate) < mojibakeScore(value) ? candidate : value;
    }

    public static JsonNode repairUtf8Mojibake(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isTextual()) {
            return objectMapper.getNodeFactory().textNode(repairUtf8Mojibake(node.asText()));
        }
        if (node.isArray()) {
            ArrayNode repaired = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                repaired.add(repairUtf8Mojibake(item, objectMapper));
            }
            return repaired;
        }
        if (node.isObject()) {
            ObjectNode repaired = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                repaired.set(field.getKey(), repairUtf8Mojibake(field.getValue(), objectMapper));
            }
            return repaired;
        }
        return node.deepCopy();
    }

    private static boolean looksLikeUtf8Mojibake(String value) {
        return value.indexOf('Ã') >= 0
            || value.indexOf('Å') >= 0
            || value.indexOf('Ä') >= 0
            || value.indexOf('â') >= 0;
    }

    private static int mojibakeScore(String value) {
        int score = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == 'Ã' || current == 'Å' || current == 'Ä' || current == 'â' || current == '�') {
                score++;
            }
        }
        return score;
    }
}