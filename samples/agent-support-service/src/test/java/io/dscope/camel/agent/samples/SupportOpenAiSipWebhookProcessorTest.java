package io.dscope.camel.agent.samples;

import java.util.Properties;
import java.util.Set;

import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.dscope.camel.agent.realtime.OpenAiRealtimeRelayClient;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallControlClient;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallControlRequestFactory;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallSessionRegistry;
import io.dscope.camel.agent.telephony.CallLifecycleState;
import io.dscope.camel.agent.telephony.OutboundSipCallResult;
import io.dscope.camel.agent.telephony.SipProviderMetadata;

class SupportOpenAiSipWebhookProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldVerifyAndAcceptIncomingWebhook() throws Exception {
        String previousApiKey = System.getProperty("openai.api.key");
        SupportCallRegistry callRegistry = new SupportCallRegistry();
        callRegistry.registerPending(
            "+15551230001",
            "conv-123",
            new OutboundSipCallResult(
                "twilio",
                "req-1",
                "tw-1",
                CallLifecycleState.REQUESTED,
                "conv-123",
                new SipProviderMetadata("twilio", "tw-1", null)
            )
        );
        OpenAiRealtimeCallSessionRegistry sessionRegistry = new OpenAiRealtimeCallSessionRegistry();
        RecordingCallControlClient client = new RecordingCallControlClient();
        RecordingRelayClient relayClient = new RecordingRelayClient();
        SupportOpenAiSipWebhookProcessor processor = new SupportOpenAiSipWebhookProcessor(
            MAPPER,
            callRegistry,
            (payload, headers) -> { },
            client,
            new OpenAiRealtimeCallControlRequestFactory(),
            sessionRegistry,
            relayClient
        );

        try (DefaultCamelContext context = new DefaultCamelContext()) {
            System.setProperty("openai.api.key", "test-openai-key");
            Properties properties = new Properties();
            properties.setProperty("agent.runtime.realtime.model", "gpt-realtime");
            properties.setProperty("agent.runtime.realtime.voice", "alloy");
            properties.setProperty("agent.runtime.telephony.openai.instructions", "You are a support agent.");
            properties.setProperty("openai.api.key", "test-openai-key");
            context.getPropertiesComponent().setInitialProperties(properties);
            context.start();

            DefaultExchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("webhook-id", "wh_1");
            exchange.getMessage().setHeader("webhook-timestamp", "1750287078");
            exchange.getMessage().setHeader("webhook-signature", "v1,dummy");
            exchange.getMessage().setBody("""
                {
                  "type": "realtime.call.incoming",
                  "data": {
                    "call_id": "call_123",
                    "sip_headers": [
                      {"name": "To", "value": "sip:+15551230001@sip.example.com"},
                      {"name": "X-Twilio-CallSid", "value": "tw-1"}
                    ]
                  }
                }
                """);

            processor.process(exchange);

            JsonNode response = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            assertTrue(response.path("processed").asBoolean());
            assertEquals("accepted", response.path("action").asText());
            assertEquals("call_123", client.acceptedCallId);
            assertEquals("conv-123", response.path("conversationId").asText());
            assertNotNull(sessionRegistry.find("call_123").orElse(null));
            assertTrue(
                Set.of(
                    CallLifecycleState.INCOMING_WEBHOOK_RECEIVED,
                    CallLifecycleState.ACCEPTED,
                    CallLifecycleState.ACTIVE
                ).contains(sessionRegistry.find("call_123").orElseThrow().state())
            );
        } finally {
            if (previousApiKey == null) {
                System.clearProperty("openai.api.key");
            } else {
                System.setProperty("openai.api.key", previousApiKey);
            }
        }
    }

    private static final class RecordingRelayClient extends OpenAiRealtimeRelayClient {
        @Override
        public void connectToCall(String conversationId, String callId, String apiKey) {
        }
    }

    private static final class RecordingCallControlClient implements OpenAiRealtimeCallControlClient {
        private String acceptedCallId;

        @Override
        public ObjectNode accept(String callId, ObjectNode payload) {
            this.acceptedCallId = callId;
            return MAPPER.createObjectNode().put("ok", true);
        }

        @Override
        public ObjectNode reject(String callId, ObjectNode payload) {
            return MAPPER.createObjectNode();
        }

        @Override
        public ObjectNode hangup(String callId) {
            return MAPPER.createObjectNode();
        }

        @Override
        public ObjectNode refer(String callId, ObjectNode payload) {
            return MAPPER.createObjectNode();
        }
    }
}