package io.dscope.camel.agent.diagnostics;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PayloadTraceProcessor implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(PayloadTraceProcessor.class);

    private final String label;
    private final boolean enabled;

    public PayloadTraceProcessor(String label, boolean enabled) {
        this.label = label;
        this.enabled = enabled;
    }

    @Override
    public void process(Exchange exchange) {
        if (!enabled) {
            return;
        }

        String body = TraceSupport.bodyText(exchange);
        LOGGER.info("{}: conversationId={}, contentType={}, path={}, chars={}, body={}",
            label,
            TraceSupport.header(exchange, "conversationId"),
            TraceSupport.header(exchange, Exchange.CONTENT_TYPE),
            TraceSupport.header(exchange, Exchange.HTTP_PATH),
            body.length(),
            TraceSupport.excerpt(body));
    }
}