package io.dscope.camel.agent.agui;

import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.model.AgUiPreRunSpec;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentAgUiPreRunTextProcessor implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentAgUiPreRunTextProcessor.class);

    private static final String DEFAULT_PROMPT = "Please help me with support.";
    private static final String DEFAULT_AGENT_ENDPOINT_URI = "agent:default?blueprint={{agent.blueprint}}";

    private final MarkdownBlueprintLoader markdownBlueprintLoader;
    private final Map<String, AgentBlueprint> blueprintCache;

    public AgentAgUiPreRunTextProcessor() {
        this.markdownBlueprintLoader = new MarkdownBlueprintLoader();
        this.blueprintCache = new ConcurrentHashMap<>();
    }

    public void clearBlueprintCache() {
        blueprintCache.clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> params = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
        if (params == null) {
            LOGGER.debug("AGUI pre-run skipped: missing params property");
            return;
        }

        String prompt = stringValue(params.get("text"));
        if (prompt.isBlank()) {
            prompt = DEFAULT_PROMPT;
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

        LOGGER.info("AGUI pre-run started: threadId={}, sessionId={}, runId={}, promptChars={}",
            threadId,
            sessionId,
            runId,
            prompt.length());

        ProducerTemplate template = exchange.getContext().createProducerTemplate();
        String outputText;
        RuntimeConfig runtimeConfig = resolveRuntimeConfig(exchange);
        try {
            Map<String, Object> headers = Map.of(
                AgentHeaders.CONVERSATION_ID, threadId,
                AgentHeaders.AGUI_SESSION_ID, sessionId,
                AgentHeaders.AGUI_RUN_ID, runId,
                AgentHeaders.AGUI_THREAD_ID, threadId
            );
            outputText = template.requestBodyAndHeaders(
                runtimeConfig.agentEndpointUri(),
                prompt,
                headers,
                String.class
            );
            LOGGER.debug("AGUI pre-run primary agent response: threadId={}, outputChars={}",
                threadId,
                outputText == null ? 0 : outputText.length());
            if (runtimeConfig.fallbackEnabled() && requiresFallback(outputText, runtimeConfig.fallbackErrorMarkers())) {
                LOGGER.info("AGUI pre-run fallback triggered: threadId={}, reason=output-match-or-empty", threadId);
                outputText = runDeterministicFallback(template, prompt, runtimeConfig);
            }
        } catch (RuntimeException runtimeFailure) {
            LOGGER.warn("AGUI pre-run primary agent failed, using fallback: threadId={}, error={}",
                threadId,
                runtimeFailure.getMessage() == null ? runtimeFailure.getClass().getSimpleName() : runtimeFailure.getMessage());
            outputText = runDeterministicFallback(template, prompt, runtimeConfig);
        }

        params.put("text", outputText);
        params.put("runId", runId);
        params.put("sessionId", sessionId);
        params.put("threadId", threadId);
        exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);
        LOGGER.info("AGUI pre-run completed: threadId={}, outputChars={}",
            threadId,
            outputText == null ? 0 : outputText.length());
    }

    private RuntimeConfig resolveRuntimeConfig(Exchange exchange) {
        AgentBlueprint blueprint = loadBlueprint(exchange);
        AgUiPreRunSpec agUiPreRunSpec = blueprint == null ? null : blueprint.aguiPreRun();

        String kbToolName = firstNonBlank(
            agUiPreRunSpec == null ? null : agUiPreRunSpec.kbToolName(),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.kb-tool-name"),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.kbToolName"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.kb-tool-name"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.kbToolName"),
            "kb.search"
        );
        String ticketToolName = firstNonBlank(
            agUiPreRunSpec == null ? null : agUiPreRunSpec.ticketToolName(),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.ticket-tool-name"),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.ticketToolName"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.ticket-tool-name"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.ticketToolName"),
            "support.ticket.open"
        );

        String kbFallbackUri = firstNonBlank(
            agUiPreRunSpec == null ? null : agUiPreRunSpec.kbUri(),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.kb-uri"),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.kbUri"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.kb-uri"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.kbUri"),
            resolveToolInvokeUri(blueprint, kbToolName),
            "direct:kb-search"
        );
        String ticketFallbackUri = firstNonBlank(
            agUiPreRunSpec == null ? null : agUiPreRunSpec.ticketUri(),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.ticket-uri"),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.ticketUri"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.ticket-uri"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.ticketUri"),
            resolveToolInvokeUri(blueprint, ticketToolName),
            "direct:support-ticket-open"
        );

        String agentEndpointUri = firstNonBlank(
            agUiPreRunSpec == null ? null : agUiPreRunSpec.agentEndpointUri(),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.agent-endpoint-uri"),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.agentEndpointUri"),
            propertyOrNull(exchange, "agent.agui.pre-run.agent-endpoint-uri"),
            propertyOrNull(exchange, "agent.agui.pre-run.agentEndpointUri"),
            DEFAULT_AGENT_ENDPOINT_URI
        );

        boolean fallbackEnabled = boolOrDefault(
            agUiPreRunSpec == null ? null : agUiPreRunSpec.fallbackEnabled(),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback-enabled"),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallbackEnabled"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback-enabled"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallbackEnabled"),
            true
        );

        List<String> ticketKeywords = firstNonEmpty(
            agUiPreRunSpec == null ? List.of() : agUiPreRunSpec.ticketKeywords(),
            csvValues(firstNonBlank(
                propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.ticket-keywords"),
                propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.ticketKeywords"),
                propertyOrNull(exchange, "agent.agui.pre-run.fallback.ticket-keywords"),
                propertyOrNull(exchange, "agent.agui.pre-run.fallback.ticketKeywords")
            )),
            csvValues("ticket,open,create,submit,escalate")
        );

        List<String> fallbackErrorMarkers = firstNonEmpty(
            agUiPreRunSpec == null ? List.of() : agUiPreRunSpec.fallbackErrorMarkers(),
            csvValues(firstNonBlank(
                propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.error-markers"),
                propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.errorMarkers"),
                propertyOrNull(exchange, "agent.agui.pre-run.fallback.error-markers"),
                propertyOrNull(exchange, "agent.agui.pre-run.fallback.errorMarkers")
            )),
            csvValues("api key is missing,openai api key,set -dopenai.api.key")
        );

        return new RuntimeConfig(
            agentEndpointUri,
            kbFallbackUri,
            ticketFallbackUri,
            fallbackEnabled,
            ticketKeywords,
            fallbackErrorMarkers
        );
    }

    private AgentBlueprint loadBlueprint(Exchange exchange) {
        String location = firstNonBlank(
            propertyOrNull(exchange, "agent.blueprint")
        );
        if (location.isBlank()) {
            return null;
        }
        try {
            return blueprintCache.computeIfAbsent(location, markdownBlueprintLoader::load);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String resolveToolInvokeUri(AgentBlueprint blueprint, String toolName) {
        if (blueprint == null || blueprint.tools() == null || toolName == null || toolName.isBlank()) {
            return "";
        }
        for (ToolSpec tool : blueprint.tools()) {
            if (tool == null || !toolName.equals(tool.name())) {
                continue;
            }
            if (tool.endpointUri() != null && !tool.endpointUri().isBlank()) {
                return tool.endpointUri().trim();
            }
            if (tool.routeId() != null && !tool.routeId().isBlank()) {
                return "direct:" + tool.routeId().trim();
            }
        }
        return "";
    }

    private boolean requiresFallback(String outputText, List<String> markers) {
        if (outputText == null || outputText.isBlank()) {
            return true;
        }
        String normalized = outputText.toLowerCase();
        for (String marker : markers) {
            if (!marker.isBlank() && normalized.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String runDeterministicFallback(ProducerTemplate template, String prompt, RuntimeConfig runtimeConfig) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("query", prompt);
        if (isTicketPrompt(prompt, runtimeConfig.ticketKeywords())) {
            LOGGER.info("AGUI pre-run deterministic fallback route=ticket: uri={}", runtimeConfig.ticketFallbackUri());
            return template.requestBody(runtimeConfig.ticketFallbackUri(), payload, String.class);
        }
        LOGGER.info("AGUI pre-run deterministic fallback route=kb: uri={}", runtimeConfig.kbFallbackUri());
        return template.requestBody(runtimeConfig.kbFallbackUri(), payload, String.class);
    }

    private boolean isTicketPrompt(String prompt, List<String> keywords) {
        String normalized = prompt == null ? "" : prompt.toLowerCase();
        for (String keyword : keywords) {
            if (!keyword.isBlank() && normalized.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private List<String> csvValues(String csv) {
        List<String> values = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return values;
        }
        for (String raw : csv.split(",")) {
            if (raw != null) {
                String value = raw.trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String propertyOrNull(Exchange exchange, String key) {
        String value = property(exchange, key, "");
        return value.isBlank() ? null : value;
    }

    private String property(Exchange exchange, String key, String defaultValue) {
        try {
            String value = exchange.getContext().resolvePropertyPlaceholders("{{" + key + ":" + defaultValue + "}}");
            if (value == null || value.isBlank() || value.contains("{{")) {
                return defaultValue;
            }
            return value.trim();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean boolOrDefault(Boolean blueprintValue, String runtimeValue1, String runtimeValue2, String runtimeValue3, String runtimeValue4, boolean defaultValue) {
        if (blueprintValue != null) {
            return blueprintValue;
        }
        String resolved = firstNonBlank(runtimeValue1, runtimeValue2, runtimeValue3, runtimeValue4);
        if (resolved.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(resolved);
    }

    @SafeVarargs
    private final List<String> firstNonEmpty(List<String>... options) {
        for (List<String> option : options) {
            if (option != null && !option.isEmpty()) {
                return option;
            }
        }
        return List.of();
    }

    private record RuntimeConfig(
        String agentEndpointUri,
        String kbFallbackUri,
        String ticketFallbackUri,
        boolean fallbackEnabled,
        List<String> ticketKeywords,
        List<String> fallbackErrorMarkers
    ) {
    }
}
