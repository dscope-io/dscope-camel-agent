package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.time.Instant;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AuditConversationSipProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldProjectSipLifecycleMetadataFromConversationEvents() throws Exception {
        InMemoryPersistenceFacade persistenceFacade = new InMemoryPersistenceFacade();
        AuditConversationSipProcessor processor = new AuditConversationSipProcessor(persistenceFacade, MAPPER);

        persistenceFacade.appendEvent(
            new AgentEvent(
                "conv-sip",
                null,
                "sip.outbound.requested",
                MAPPER.readTree("""
                    {
                      "conversationKind": "sip",
                      "provider": "twilio",
                      "providerReference": "tw-123",
                      "destination": "+15551230001",
                      "callerId": "+15557650002",
                      "state": "REQUESTED",
                      "openai": {
                        "projectId": "proj_support",
                        "sipUri": "sip:proj_support@sip.api.openai.com;transport=tls"
                      },
                      "twilio": {
                        "trunkName": "support-trunk",
                        "fromNumber": "+15557650002"
                      }
                    }
                    """),
                Instant.now()
            ),
            "evt-1"
        );
        persistenceFacade.appendEvent(
            new AgentEvent(
                "conv-sip",
                null,
                "sip.openai.incoming",
                MAPPER.readTree("""
                    {
                      "provider": "twilio",
                      "providerReference": "tw-123",
                      "destination": "+15551230001",
                      "state": "INCOMING_WEBHOOK_RECEIVED",
                      "openai": {
                        "projectId": "proj_support",
                        "callId": "call_123"
                      }
                    }
                    """),
                Instant.now()
            ),
            "evt-2"
        );
        persistenceFacade.appendEvent(
            new AgentEvent(
                "conv-sip",
                null,
                "sip.openai.monitor",
                MAPPER.readTree("""
                    {
                      "provider": "twilio",
                      "state": "ACTIVE",
                      "monitorConnected": true,
                      "openai": {
                        "callId": "call_123",
                        "model": "gpt-realtime",
                        "voice": "alloy"
                      }
                    }
                    """),
                Instant.now()
            ),
            "evt-3"
        );

        var exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getMessage().setHeader("conversationId", "conv-sip");

        processor.process(exchange);

        JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
        Assertions.assertTrue(body.path("present").asBoolean());
        Assertions.assertEquals("twilio", body.path("sip").path("providerName").asText());
        Assertions.assertEquals("call_123", body.path("sip").path("openAiCallId").asText());
        Assertions.assertEquals("proj_support", body.path("sip").path("openAiProjectId").asText());
        Assertions.assertEquals("ACTIVE", body.path("sip").path("lifecycleState").asText());
        Assertions.assertTrue(body.path("sip").path("monitorConnected").asBoolean());
        Assertions.assertEquals(3, body.path("eventCount").asInt());
    }
}