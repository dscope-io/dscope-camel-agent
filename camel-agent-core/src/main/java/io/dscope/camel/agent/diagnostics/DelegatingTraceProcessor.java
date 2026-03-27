package io.dscope.camel.agent.diagnostics;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DelegatingTraceProcessor implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(DelegatingTraceProcessor.class);

    private final String label;
    private final Processor delegate;
    private final boolean enabled;
    private final ObjectMapper objectMapper;

    public DelegatingTraceProcessor(String label, Processor delegate, boolean enabled) {
        this.label = label;
        this.delegate = delegate;
        this.enabled = enabled;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        if (!enabled) {
            delegate.process(exchange);
            return;
        }

        LOGGER.info("{} request: conversationId={}, sessionId={}, runId={}, type={}, limit={}",
            label,
            TraceSupport.header(exchange, "conversationId"),
            TraceSupport.header(exchange, "sessionId"),
            TraceSupport.header(exchange, "runId"),
            TraceSupport.header(exchange, "type"),
            TraceSupport.header(exchange, "limit"));

        delegate.process(exchange);

        LOGGER.info("{} response: {}", label, TraceSupport.summarizeProcessorResponse(exchange, objectMapper));
    }
}