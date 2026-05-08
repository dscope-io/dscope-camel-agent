package io.dscope.camel.agent.telephony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

class TelephonyIdentityMetadataProcessorTest {

    @Test
    void promotesCallerIdentityIntoSessionMetadataAndHeaders() throws Exception {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getMessage().setBody("{\"call\":{\"from\":\"+421901123456\"},\"session\":{\"metadata\":{\"twilio\":{}}}}");

        new TelephonyIdentityMetadataProcessor().process(exchange);

        String body = exchange.getMessage().getBody(String.class);
        assertEquals("+421901123456", exchange.getMessage().getHeader("callerId"));
        assertEquals("+421901123456", exchange.getMessage().getHeader("fromNumber"));
        assertEquals("+421901123456", exchange.getMessage().getHeader("twilio.fromNumber"));
        assertEquals("application/json", exchange.getMessage().getHeader(Exchange.CONTENT_TYPE));
        assertTrue(body.contains("\"sip\""));
        assertTrue(body.contains("\"callerId\":\"+421901123456\""));
        assertTrue(body.contains("\"fromNumber\":\"+421901123456\""));
    }
}