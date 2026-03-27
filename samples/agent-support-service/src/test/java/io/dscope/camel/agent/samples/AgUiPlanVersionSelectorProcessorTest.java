package io.dscope.camel.agent.samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.dscope.camel.agent.agui.AgentAgUiExchangeProperties;
import io.dscope.camel.agent.config.AgentHeaders;
import java.util.HashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

class AgUiPlanVersionSelectorProcessorTest {

    private final AgUiPlanVersionSelectorProcessor processor = new AgUiPlanVersionSelectorProcessor();

    @Test
    void selectsCalendarV1FromParams() throws Exception {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        Map<String, Object> params = new HashMap<>();
        params.put("text", "What service slots are available tomorrow morning?");
        params.put("planVersion", "v1");
        exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

        processor.process(exchange);

        Map<String, Object> out = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
        assertEquals("calendar", out.get("planName"));
        assertEquals("v1", out.get("planVersion"));
        assertEquals("What service slots are available tomorrow morning?", out.get("text"));
        assertEquals("calendar", exchange.getMessage().getHeader(AgentHeaders.PLAN_NAME));
        assertEquals("v1", exchange.getMessage().getHeader(AgentHeaders.PLAN_VERSION));
    }

    @Test
    void selectsPlanFromHeaders() throws Exception {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        Map<String, Object> params = new HashMap<>();
        params.put("text", "Booking: rdobrik@gmail.com, oil change");
        exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);
        exchange.getMessage().setHeader(AgentHeaders.PLAN_NAME, "calendar");
        exchange.getMessage().setHeader(AgentHeaders.PLAN_VERSION, "v2");

        processor.process(exchange);

        Map<String, Object> out = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
        assertEquals("calendar", out.get("planName"));
        assertEquals("v2", out.get("planVersion"));
        assertEquals("Booking: rdobrik@gmail.com, oil change", out.get("text"));
    }

    @Test
    void leavesPromptUntouchedWithoutVersionMarker() throws Exception {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        Map<String, Object> params = new HashMap<>();
        params.put("text", "What service slots are available tomorrow morning?");
        exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

        processor.process(exchange);

        Map<String, Object> out = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
        assertEquals("What service slots are available tomorrow morning?", out.get("text"));
        assertNull(exchange.getMessage().getHeader(AgentHeaders.PLAN_NAME));
        assertNull(exchange.getMessage().getHeader(AgentHeaders.PLAN_VERSION));
    }
}