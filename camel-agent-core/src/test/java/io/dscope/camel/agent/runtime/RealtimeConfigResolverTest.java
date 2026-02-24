package io.dscope.camel.agent.runtime;

import io.dscope.camel.agent.model.RealtimeSpec;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RealtimeConfigResolverTest {

    @Test
    void shouldReturnNullWhenBlueprintAndPropertiesAreMissing() {
        RealtimeSpec resolved = RealtimeConfigResolver.resolve(null, key -> null);
        Assertions.assertNull(resolved);
    }

    @Test
    void shouldResolveFromApplicationPropertiesWhenBlueprintMissing() {
        Map<String, String> properties = new HashMap<>();
        properties.put("agent.runtime.realtime.provider", "openai");
        properties.put("agent.runtime.realtime.model", "gpt-4o-realtime-preview");
        properties.put("agent.runtime.realtime.voice", "alloy");
        properties.put("agent.runtime.realtime.transport", "server-relay");
        properties.put("agent.runtime.realtime.input-audio-format", "pcm16");
        properties.put("agent.runtime.realtime.output-audio-format", "pcm16");
        properties.put("agent.runtime.realtime.retention-policy", "metadata_transcript");
        properties.put("agent.runtime.realtime.reconnect.max-send-retries", "3");
        properties.put("agent.runtime.realtime.reconnect.max-reconnects", "8");
        properties.put("agent.runtime.realtime.reconnect.initial-backoff-ms", "150");
        properties.put("agent.runtime.realtime.reconnect.max-backoff-ms", "2000");

        RealtimeSpec resolved = RealtimeConfigResolver.resolve(null, properties::get);

        Assertions.assertNotNull(resolved);
        Assertions.assertEquals("openai", resolved.provider());
        Assertions.assertEquals("gpt-4o-realtime-preview", resolved.model());
        Assertions.assertEquals("alloy", resolved.voice());
        Assertions.assertEquals("server-relay", resolved.transport());
        Assertions.assertEquals("pcm16", resolved.inputAudioFormat());
        Assertions.assertEquals("pcm16", resolved.outputAudioFormat());
        Assertions.assertEquals("metadata_transcript", resolved.retentionPolicy());
        Assertions.assertEquals(3, resolved.reconnectMaxSendRetries());
        Assertions.assertEquals(8, resolved.reconnectMaxReconnects());
        Assertions.assertEquals(150L, resolved.reconnectInitialBackoffMs());
        Assertions.assertEquals(2000L, resolved.reconnectMaxBackoffMs());
    }

    @Test
    void shouldPreferBlueprintValuesAndFillMissingFromProperties() {
        RealtimeSpec blueprint = new RealtimeSpec(
            "openai",
            "gpt-4o-realtime-preview",
            null,
            "server-relay",
            null,
            "pcm16",
            null,
            "metadata_transcript"
        );

        Map<String, String> properties = new HashMap<>();
        properties.put("agent.runtime.realtime.voice", "verse");
        properties.put("agent.runtime.realtime.endpoint-uri", "wss://api.openai.com/v1/realtime");
        properties.put("agent.runtime.realtime.output-audio-format", "pcm16");
        properties.put("agent.runtime.realtime.model", "should-not-override");
        properties.put("agent.runtime.realtime.reconnect.max-send-retries", "5");

        RealtimeSpec resolved = RealtimeConfigResolver.resolve(blueprint, properties::get);

        Assertions.assertNotNull(resolved);
        Assertions.assertEquals("gpt-4o-realtime-preview", resolved.model());
        Assertions.assertEquals("verse", resolved.voice());
        Assertions.assertEquals("wss://api.openai.com/v1/realtime", resolved.endpointUri());
        Assertions.assertEquals("pcm16", resolved.outputAudioFormat());
        Assertions.assertEquals(5, resolved.reconnectMaxSendRetries());
    }

    @Test
    void shouldResolveReconnectAliasKeys() {
        Map<String, String> properties = new HashMap<>();
        properties.put("agent.realtime.provider", "openai");
        properties.put("agent.realtime.model", "gpt-4o-realtime-preview");
        properties.put("agent.realtime.reconnect.maxSendRetries", "2");
        properties.put("agent.realtime.reconnect.maxReconnects", "4");
        properties.put("agent.realtime.reconnect.initialBackoffMs", "100");
        properties.put("agent.realtime.reconnect.maxBackoffMs", "1000");

        RealtimeSpec resolved = RealtimeConfigResolver.resolve(null, properties::get);

        Assertions.assertNotNull(resolved);
        Assertions.assertEquals("openai", resolved.provider());
        Assertions.assertEquals("gpt-4o-realtime-preview", resolved.model());
        Assertions.assertEquals(2, resolved.reconnectMaxSendRetries());
        Assertions.assertEquals(4, resolved.reconnectMaxReconnects());
        Assertions.assertEquals(100L, resolved.reconnectInitialBackoffMs());
        Assertions.assertEquals(1000L, resolved.reconnectMaxBackoffMs());
    }

    @Test
    void shouldFillOnlyMissingReconnectFieldsFromRuntimeProperties() {
        RealtimeSpec blueprint = new RealtimeSpec(
            "openai",
            "gpt-4o-realtime-preview",
            "alloy",
            "server-relay",
            "wss://api.openai.com/v1/realtime",
            "pcm16",
            "pcm16",
            "metadata_transcript",
            1,
            null,
            200L,
            null
        );

        Map<String, String> properties = new HashMap<>();
        properties.put("agent.runtime.realtime.reconnect.max-send-retries", "9");
        properties.put("agent.runtime.realtime.reconnect.max-reconnects", "6");
        properties.put("agent.runtime.realtime.reconnect.initial-backoff-ms", "900");
        properties.put("agent.runtime.realtime.reconnect.max-backoff-ms", "5000");

        RealtimeSpec resolved = RealtimeConfigResolver.resolve(blueprint, properties::get);

        Assertions.assertNotNull(resolved);
        Assertions.assertEquals(1, resolved.reconnectMaxSendRetries());
        Assertions.assertEquals(6, resolved.reconnectMaxReconnects());
        Assertions.assertEquals(200L, resolved.reconnectInitialBackoffMs());
        Assertions.assertEquals(5000L, resolved.reconnectMaxBackoffMs());
    }
}
