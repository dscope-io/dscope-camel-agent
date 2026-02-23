package io.dscope.camel.agent;

import io.dscope.camel.agent.registry.CorrelationRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CorrelationRegistryTest {

    @Test
    void shouldResolveBoundCorrelationTypes() {
        CorrelationRegistry registry = new CorrelationRegistry();
        registry.bind("conversation-1", "runId", "run-1");
        registry.bind("conversation-1", "sessionId", "session-1");

        Assertions.assertEquals("run-1", registry.resolve("conversation-1", "runId", "fallback"));
        Assertions.assertEquals("session-1", registry.resolve("conversation-1", "sessionId", "fallback"));
    }

    @Test
    void shouldReturnFallbackWhenMissing() {
        CorrelationRegistry registry = new CorrelationRegistry();
        Assertions.assertEquals("fallback", registry.resolve("conversation-missing", "runId", "fallback"));
    }

    @Test
    void shouldClearCorrelationsForSource() {
        CorrelationRegistry registry = new CorrelationRegistry();
        registry.bind("conversation-2", "runId", "run-2");
        registry.clear("conversation-2");

        Assertions.assertEquals("fallback", registry.resolve("conversation-2", "runId", "fallback"));
    }
}