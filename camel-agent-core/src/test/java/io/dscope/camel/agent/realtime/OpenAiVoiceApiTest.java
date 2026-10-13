package io.dscope.camel.agent.realtime;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenAiVoiceApiTest {

    @Test
    void shouldResolveSupportedApisAndDefaults() {
        Assertions.assertEquals(OpenAiVoiceApi.REALTIME, OpenAiVoiceApi.fromConfig(null));
        Assertions.assertEquals(OpenAiVoiceApi.REALTIME, OpenAiVoiceApi.fromConfig("realtime"));
        Assertions.assertEquals(OpenAiVoiceApi.LIVE, OpenAiVoiceApi.fromConfig("live"));
        Assertions.assertEquals("gpt-realtime-2.1", OpenAiVoiceApi.REALTIME.validateModel(null));
        Assertions.assertEquals("gpt-live-1", OpenAiVoiceApi.LIVE.validateModel(null));
    }

    @Test
    void shouldRejectUnknownApisAndMismatchedModels() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> OpenAiVoiceApi.fromConfig("responses"));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> OpenAiVoiceApi.LIVE.validateModel("gpt-realtime-2.1"));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> OpenAiVoiceApi.REALTIME.validateModel("gpt-live-1"));
    }
}
