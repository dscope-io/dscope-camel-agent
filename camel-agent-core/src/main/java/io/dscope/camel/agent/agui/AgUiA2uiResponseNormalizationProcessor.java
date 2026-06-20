package io.dscope.camel.agent.agui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Normalizes AGUI SSE payloads so structured A2UI content can be consumed consistently by browser clients.
 */
public class AgUiA2uiResponseNormalizationProcessor implements Processor {

    public static final String BEAN_NAME = "agUiA2uiResponseNormalizationProcessor";
    private static final List<String> TEXT_FIELDS = List.of("text", "content", "message", "assistantMessage", "delta");

    private final ObjectMapper objectMapper;

    public AgUiA2uiResponseNormalizationProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String body = exchange.getMessage().getBody(String.class);
        if (body == null || body.isBlank()) {
            return;
        }
        exchange.getMessage().setBody(normalizeSse(body));
    }

    String normalizeSse(String body) {
        List<String> output = new ArrayList<>();
        List<String> block = new ArrayList<>();
        for (String line : body.split("\\r?\\n", -1)) {
            if (line.isEmpty()) {
                output.addAll(normalizeBlock(block));
                output.add("");
                block.clear();
                continue;
            }
            block.add(line);
        }
        if (!block.isEmpty()) {
            output.addAll(normalizeBlock(block));
        }
        return String.join("\n", output);
    }

    private List<String> normalizeBlock(List<String> block) {
        if (block.isEmpty()) {
            return List.of();
        }
        List<String> passthrough = new ArrayList<>();
        List<String> dataLines = new ArrayList<>();
        for (String line : block) {
            if (line.startsWith("data:")) {
                dataLines.add(line.substring(5).trim());
            } else {
                passthrough.add(line);
            }
        }
        if (dataLines.isEmpty()) {
            return new ArrayList<>(block);
        }
        String joined = String.join("\n", dataLines);
        JsonNode parsed = parseJson(joined);
        if (parsed == null || !parsed.isObject()) {
            return new ArrayList<>(block);
        }
        ObjectNode normalized = normalizeEventData((ObjectNode) parsed);
        List<String> rebuilt = new ArrayList<>(passthrough);
        rebuilt.add("data: " + writeJson(normalized));
        return rebuilt;
    }

    private ObjectNode normalizeEventData(ObjectNode eventData) {
        ObjectNode normalized = eventData.deepCopy();
        JsonNode directPayload = normalizePayloadIfNeeded(normalized);
        if (directPayload != normalized) {
            return (ObjectNode) directPayload;
        }
        for (String fieldName : TEXT_FIELDS) {
            JsonNode field = normalized.get(fieldName);
            if (field == null || !field.isTextual()) {
                continue;
            }
            JsonNode parsedField = parseJson(field.asText());
            if (parsedField == null) {
                continue;
            }
            JsonNode normalizedField = normalizePayloadIfNeeded(parsedField);
            if (normalizedField != parsedField) {
                normalized.put(fieldName, writeJson(normalizedField));
            }
        }
        return normalized;
    }

    private JsonNode normalizePayloadIfNeeded(JsonNode payload) {
        if (!(payload instanceof ObjectNode objectNode)) {
            return payload;
        }
        ArrayNode operations = arrayValue(objectNode.get("a2ui_operations"));
        if (operations == null) {
            operations = arrayValue(objectNode.get("a2uiOperations"));
        }
        if (operations != null) {
            ObjectNode normalized = objectNode.deepCopy();
            ArrayNode normalizedOperations = JsonNodeFactory.instance.arrayNode();
            for (JsonNode operation : operations) {
                normalizedOperations.add(normalizeOperation(operation));
            }
            normalized.set("a2ui_operations", normalizedOperations);
            normalized.remove("a2uiOperations");
            return normalized;
        }
        if (looksLikeSurface(objectNode)) {
            return normalizeSurface(objectNode);
        }
        return payload;
    }

    private JsonNode normalizeSurface(ObjectNode surface) {
        ObjectNode operation = JsonNodeFactory.instance.objectNode();
        operation.setAll(surface.deepCopy());
        return normalizeOperation(operation);
    }

    private ObjectNode normalizeOperation(JsonNode operationNode) {
        ObjectNode operation = operationNode instanceof ObjectNode objectNode ? objectNode.deepCopy() : JsonNodeFactory.instance.objectNode();
        String surfaceId = firstNonBlank(
            textValue(operation.get("surfaceId")),
            textValue(objectValue(operation.get("surface")).path("surfaceId"))
        );
        if (surfaceId == null || surfaceId.isBlank()) {
            return operation;
        }

        ArrayNode components = arrayValue(operation.get("components"));
        if (components == null) {
            components = JsonNodeFactory.instance.arrayNode();
        } else {
            components = components.deepCopy();
        }

        ObjectNode firstComponent = components.isEmpty()
            ? JsonNodeFactory.instance.objectNode()
            : objectValue(components.get(0)).deepCopy();
        ObjectNode data = objectValue(firstComponent.get("data")).deepCopy();
        mergeMissing(data, objectValue(operation.get("data")));
        mergeMissing(data, objectValue(operation.get("value")));
        String existingTitle = firstNonBlank(textValue(data.get("title")), textValue(operation.get("title")));
        if (existingTitle != null && !existingTitle.isBlank()) {
            data.put("title", existingTitle);
        }

        if (!firstComponent.hasNonNull("id")) {
            firstComponent.put("id", "root");
        }
        if (!firstComponent.hasNonNull("type")) {
            firstComponent.put("type", "Column");
        }
        firstComponent.set("data", data);
        if (components.isEmpty()) {
            components.add(firstComponent);
        } else {
            components.set(0, firstComponent);
        }

        String existingSummary = firstNonBlank(textValue(operation.get("summary")), textValue(data.get("summary")));
        if (existingSummary != null && !existingSummary.isBlank()) {
            operation.put("summary", existingSummary);
        }

        operation.put("surfaceId", surfaceId);
        operation.set("components", components);
        operation.remove("data");
        operation.remove("value");
        operation.remove("title");
        return operation;
    }

    private boolean looksLikeSurface(ObjectNode node) {
        return node.has("surfaceId")
            && (node.has("components") || node.has("data") || node.has("value") || node.has("title"));
    }

    private void mergeMissing(ObjectNode target, ObjectNode source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : iterable(source.fields())) {
            if (!target.has(entry.getKey())) {
                target.set(entry.getKey(), entry.getValue());
            }
        }
    }

    private <T> Iterable<T> iterable(java.util.Iterator<T> iterator) {
        return () -> iterator;
    }

    private ArrayNode arrayValue(JsonNode node) {
        return node instanceof ArrayNode arrayNode ? arrayNode : null;
    }

    private ObjectNode objectValue(JsonNode node) {
        return node instanceof ObjectNode objectNode ? objectNode : JsonNodeFactory.instance.objectNode();
    }

    private String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private JsonNode parseJson(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        for (int depth = 0; depth < 4; depth++) {
            if (candidate.isEmpty()) {
                return null;
            }
            if (candidate.startsWith("{") || candidate.startsWith("[")) {
                try {
                    return objectMapper.readTree(candidate);
                } catch (JsonProcessingException ignored) {
                    return null;
                }
            }
            if (!(candidate.startsWith("\"") && candidate.endsWith("\""))) {
                return null;
            }
            try {
                JsonNode nested = objectMapper.readTree(candidate);
                if (!nested.isTextual()) {
                    return nested;
                }
                candidate = nested.asText().trim();
            } catch (JsonProcessingException ignored) {
                return null;
            }
        }
        return null;
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize normalized A2UI payload", exception);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
