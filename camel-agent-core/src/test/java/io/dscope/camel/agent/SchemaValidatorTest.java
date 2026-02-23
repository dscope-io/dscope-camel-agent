package io.dscope.camel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.validation.SchemaValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldRejectMissingRequiredField() {
        SchemaValidator validator = new SchemaValidator();
        var schema = mapper.createObjectNode();
        schema.putArray("required").add("query");
        var payload = mapper.createObjectNode();

        Assertions.assertThrows(IllegalArgumentException.class,
            () -> validator.validate(schema, payload, "test"));
    }
}
