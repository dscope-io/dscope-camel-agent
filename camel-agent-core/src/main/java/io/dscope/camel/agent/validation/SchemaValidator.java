package io.dscope.camel.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;

public class SchemaValidator {

    public void validate(JsonNode schema, JsonNode data, String context) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return;
        }
        JsonNode required = schema.path("required");
        if (!required.isArray()) {
            return;
        }
        Iterator<JsonNode> iterator = required.elements();
        while (iterator.hasNext()) {
            String field = iterator.next().asText();
            if (data == null || data.path(field).isMissingNode() || data.path(field).isNull()) {
                throw new IllegalArgumentException("Schema validation failed for " + context + ": missing required field '" + field + "'");
            }
        }
    }
}
