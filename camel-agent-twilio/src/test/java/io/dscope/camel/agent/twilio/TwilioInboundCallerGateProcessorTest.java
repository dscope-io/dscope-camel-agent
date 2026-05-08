package io.dscope.camel.agent.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

class TwilioInboundCallerGateProcessorTest {

    @Test
    void allowsNormalizedConfiguredCaller() throws Exception {
        TwilioInboundCallerGateProcessor processor = new TwilioInboundCallerGateProcessor("+14087771111");
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getMessage().setBody("{\"from\":\"(408) 777-1111\"}");

        processor.process(exchange);

        assertEquals("+14087771111", exchange.getMessage().getHeader("twilio.inboundCaller"));
        assertNull(exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertNull(exchange.getProperty("abortRoute"));
    }

    @Test
    void deniesCallerOutsideAllowlist() throws Exception {
        TwilioInboundCallerGateProcessor processor = new TwilioInboundCallerGateProcessor("+14087771111");
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getMessage().setBody("{\"call\":{\"from\":\"+14087772222\"}}");

        processor.process(exchange);

        assertEquals(403, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals(true, exchange.getProperty("abortRoute"));
        assertEquals("{\"error\":\"Inbound caller is not allowed\"}", exchange.getMessage().getBody(String.class));
    }
}