package io.dscope.camel.agent.diagnostics;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class ResponseTraceProcessor implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseTraceProcessor.class);

    private final String label;
    private final boolean enabled;
    private final ObjectMapper objectMapper;

    public ResponseTraceProcessor(String label, boolean enabled) {
        this.label = label;
        this.enabled = enabled;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void process(Exchange exchange) {
        if (!enabled) {
            return;
        }
        LOGGER.info("{}: status={}, summary={}",
            label,
            exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class),
            TraceSupport.summarizeProcessorResponse(exchange, objectMapper));
    }
}