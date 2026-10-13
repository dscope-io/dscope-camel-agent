package io.dscope.camel.agent.realtime.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenAiRealtimeCallControlRequestFactoryTest {

    @Test
    void shouldPutVoiceUnderGaAudioOutput() {
        OpenAiRealtimeCallControlRequestFactory factory = new OpenAiRealtimeCallControlRequestFactory();
        ObjectNode request = factory.acceptRequest(
            "gpt-realtime-2.1",
            "Be concise",
            "marin",
            null,
            null
        );

        Assertions.assertEquals("realtime", request.path("type").asText());
        Assertions.assertEquals("gpt-realtime-2.1", request.path("model").asText());
        Assertions.assertEquals("marin", request.path("audio").path("output").path("voice").asText());
        Assertions.assertFalse(request.has("voice"));
    }
}