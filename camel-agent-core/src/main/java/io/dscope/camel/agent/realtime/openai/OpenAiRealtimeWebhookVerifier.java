package io.dscope.camel.agent.realtime.openai;

import java.util.Map;

public interface OpenAiRealtimeWebhookVerifier {

    void verify(String payload, Map<String, String> headers) throws Exception;
}