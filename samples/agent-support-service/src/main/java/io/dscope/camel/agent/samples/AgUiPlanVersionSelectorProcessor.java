package io.dscope.camel.agent.samples;

import io.dscope.camel.agent.agui.AgentAgUiExchangeProperties;
import io.dscope.camel.agent.config.AgentHeaders;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgUiPlanVersionSelectorProcessor implements Processor {

    private static final Logger LOG = LoggerFactory.getLogger(AgUiPlanVersionSelectorProcessor.class);
    private static final String CALENDAR_PLAN = "calendar";
    private static final String HEADER_PLAN_NAME = "x-agent-plan-name";
    private static final String HEADER_PLAN_VERSION = "x-agent-plan-version";

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        if (exchange == null) {
            return;
        }
        Map<String, Object> params = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
        if (params == null || params.isEmpty()) {
            return;
        }

        String planName = firstNonBlank(
            stringValue(params.get("planName")),
            headerValue(exchange, AgentHeaders.PLAN_NAME),
            headerValue(exchange, HEADER_PLAN_NAME)
        );
        String planVersion = firstNonBlank(
            stringValue(params.get("planVersion")),
            headerValue(exchange, AgentHeaders.PLAN_VERSION),
            headerValue(exchange, HEADER_PLAN_VERSION)
        );

        if (planName == null && planVersion == null) {
            return;
        }

        String normalizedPlanName = planName == null ? CALENDAR_PLAN : planName.trim();
        String normalizedPlanVersion = planVersion == null ? null : planVersion.trim().toLowerCase();

        params.put("planName", normalizedPlanName);
        if (normalizedPlanVersion != null) {
            params.put("planVersion", normalizedPlanVersion);
        }
        exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

        exchange.getMessage().setHeader(AgentHeaders.PLAN_NAME, normalizedPlanName);
        if (normalizedPlanVersion != null) {
            exchange.getMessage().setHeader(AgentHeaders.PLAN_VERSION, normalizedPlanVersion);
        }

        LOG.info("AGUI plan selector applied: planName={}, planVersion={}",
            normalizedPlanName,
            normalizedPlanVersion);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String headerValue(Exchange exchange, String headerName) {
        if (exchange == null || headerName == null || headerName.isBlank()) {
            return null;
        }
        return exchange.getMessage() == null ? null : exchange.getMessage().getHeader(headerName, String.class);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}