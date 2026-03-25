package io.dscope.camel.agent.samples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.telephony.CallLifecycleState;
import io.dscope.camel.agent.telephony.OutboundSipCallRequest;
import io.dscope.camel.agent.telephony.OutboundSipCallResult;
import io.dscope.camel.agent.telephony.SipProviderClient;
import io.dscope.camel.agent.telephony.SipProviderMetadata;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

class SupportOutboundCallProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldReturnStructuredAsyncCallResult() throws Exception {
        SupportCallRegistry registry = new SupportCallRegistry();
        SupportOutboundCallProcessor processor = new SupportOutboundCallProcessor(MAPPER, registry) {
            @Override
            protected SipProviderClient createProviderClient(Exchange exchange) {
                return new SipProviderClient() {
                    @Override
                    public String providerName() {
                        return "stub";
                    }

                    @Override
                    public OutboundSipCallResult placeOutboundCall(OutboundSipCallRequest request) {
                        return new OutboundSipCallResult(
                            "stub",
                            "req-1",
                            "prov-1",
                            CallLifecycleState.REQUESTED,
                            request.requestedConversationId(),
                            new SipProviderMetadata("stub", "prov-1", null)
                        );
                    }
                };
            }
        };

        DefaultCamelContext context = new DefaultCamelContext();
        DefaultExchange exchange = new DefaultExchange(context);
        exchange.getMessage().setHeader(AgentHeaders.CONVERSATION_ID, "conv-123");
        exchange.getMessage().setBody("{\"destination\":\"+15551230001\",\"query\":\"call customer\"}");

        processor.process(exchange);

        JsonNode response = MAPPER.readTree(exchange.getMessage().getBody(String.class));
        assertEquals("stub", response.path("provider").asText());
        assertEquals("req-1", response.path("requestId").asText());
        assertEquals("prov-1", response.path("providerReference").asText());
        assertEquals("requested", response.path("status").asText());
        assertEquals("conv-123", response.path("conversationId").asText());
        assertEquals("+15551230001", response.path("destination").asText());
        assertEquals(1, registry.snapshot().size());
    }
}