package io.dscope.camel.agent.samples;

import io.dscope.camel.agui.config.AgUiExchangeProperties;
import io.dscope.camel.agent.config.AgentHeaders;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;

final class SupportAgUiPreRunTextProcessor implements Processor {

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> params = exchange.getProperty(AgUiExchangeProperties.PARAMS, Map.class);
        if (params == null) {
            return;
        }

        String prompt = stringValue(params.get("text"));
        if (prompt.isBlank()) {
            prompt = "Please help me with support.";
        }

        String threadId = firstNonBlank(
            stringValue(params.get("threadId")),
            stringValue(params.get("sessionId")),
            UUID.randomUUID().toString()
        );
        String sessionId = firstNonBlank(
            stringValue(params.get("sessionId")),
            threadId
        );
        String runId = firstNonBlank(
            stringValue(params.get("runId")),
            UUID.randomUUID().toString()
        );

        ProducerTemplate template = exchange.getContext().createProducerTemplate();
        String outputText;
        try {
            Map<String, Object> headers = Map.of(
                AgentHeaders.CONVERSATION_ID, threadId,
                AgentHeaders.AGUI_SESSION_ID, sessionId,
                AgentHeaders.AGUI_RUN_ID, runId,
                AgentHeaders.AGUI_THREAD_ID, threadId
            );
            outputText = template.requestBodyAndHeaders(
                "agent:support?blueprint={{agent.blueprint}}",
                prompt,
                headers,
                String.class
            );
            if (requiresFallback(outputText)) {
                outputText = runDeterministicFallback(template, prompt);
            }
        } catch (Exception ignored) {
            outputText = runDeterministicFallback(template, prompt);
        }

        params.put("text", outputText);
        params.put("runId", runId);
        params.put("sessionId", sessionId);
        params.put("threadId", threadId);
        exchange.setProperty(AgUiExchangeProperties.PARAMS, params);
    }

    private boolean requiresFallback(String outputText) {
        if (outputText == null || outputText.isBlank()) {
            return true;
        }
        String normalized = outputText.toLowerCase();
        return normalized.contains("api key is missing")
            || normalized.contains("openai api key")
            || normalized.contains("set -dopenai.api.key");
    }

    private String runDeterministicFallback(ProducerTemplate template, String prompt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("query", prompt);
        if (isTicketPrompt(prompt)) {
            return template.requestBody("direct:support-ticket-open", payload, String.class);
        }
        return template.requestBody("direct:kb-search", payload, String.class);
    }

    private boolean isTicketPrompt(String prompt) {
        String normalized = prompt == null ? "" : prompt.toLowerCase();
        return normalized.contains("ticket")
            || normalized.contains("open")
            || normalized.contains("create")
            || normalized.contains("submit")
            || normalized.contains("escalate");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}