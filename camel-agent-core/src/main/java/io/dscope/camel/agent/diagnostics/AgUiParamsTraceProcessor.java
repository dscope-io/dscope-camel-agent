package io.dscope.camel.agent.diagnostics;

import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dscope.camel.agent.agui.AgentAgUiExchangeProperties;

public class AgUiParamsTraceProcessor implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgUiParamsTraceProcessor.class);

    private final boolean enabled;

    public AgUiParamsTraceProcessor(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void process(Exchange exchange) {
        if (!enabled) {
            return;
        }

        Map<String, Object> params = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
        if (params == null || params.isEmpty()) {
            LOGGER.info("AGUI parsed params: missing agui.params property");
            return;
        }

        String text = TraceSupport.stringValue(params.get("text"));
        List<String> stateMessages = TraceSupport.summarizeStateMessages(params);
        LOGGER.info("AGUI parsed params: threadId={}, sessionId={}, runId={}, textChars={}, text={}, stateMessages={}",
            TraceSupport.stringValue(params.get("threadId")),
            TraceSupport.stringValue(params.get("sessionId")),
            TraceSupport.stringValue(params.get("runId")),
            text.length(),
            TraceSupport.excerpt(text),
            stateMessages);
    }
}