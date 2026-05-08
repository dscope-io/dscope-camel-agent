package io.dscope.camel.agent.agui;

import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dscope.camel.agent.config.AgentHeaders;

public final class AgUiPlanVersionSelectorProcessor implements Processor {

    private static final Logger LOG = LoggerFactory.getLogger(AgUiPlanVersionSelectorProcessor.class);
    private static final String HEADER_PLAN_NAME_ALIAS = "x-agent-plan-name";
    private static final String HEADER_PLAN_VERSION_ALIAS = "x-agent-plan-version";

    private final String defaultPlanName;

    public AgUiPlanVersionSelectorProcessor() {
        this(null);
    }

    public AgUiPlanVersionSelectorProcessor(String defaultPlanName) {
        this.defaultPlanName = blankToNull(defaultPlanName);
    }

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
            headerValue(exchange, HEADER_PLAN_NAME_ALIAS),
            defaultPlanName
        );
        String planVersion = firstNonBlank(
            stringValue(params.get("planVersion")),
            headerValue(exchange, AgentHeaders.PLAN_VERSION),
            headerValue(exchange, HEADER_PLAN_VERSION_ALIAS)
        );

        if (planName == null && planVersion == null) {
            return;
        }

        String normalizedPlanName = planName == null ? null : planName.trim();
        String normalizedPlanVersion = planVersion == null ? null : planVersion.trim().toLowerCase();

        if (normalizedPlanName != null) {
            params.put("planName", normalizedPlanName);
            exchange.getMessage().setHeader(AgentHeaders.PLAN_NAME, normalizedPlanName);
        }
        if (normalizedPlanVersion != null) {
            params.put("planVersion", normalizedPlanVersion);
            exchange.getMessage().setHeader(AgentHeaders.PLAN_VERSION, normalizedPlanVersion);
        }
        exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

        LOG.info("AGUI plan selector applied: planName={}, planVersion={}", normalizedPlanName, normalizedPlanVersion);
    }

    private static String headerValue(Exchange exchange, String headerName) {
        if (exchange == null || headerName == null || headerName.isBlank() || exchange.getMessage() == null) {
            return null;
        }
        return exchange.getMessage().getHeader(headerName, String.class);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}