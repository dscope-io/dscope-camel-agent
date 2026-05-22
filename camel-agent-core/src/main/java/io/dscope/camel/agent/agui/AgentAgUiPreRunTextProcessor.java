package io.dscope.camel.agent.agui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.model.AgUiPreRunSpec;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.ExceptionAction;
import io.dscope.camel.agent.model.ExceptionCategory;
import io.dscope.camel.agent.model.ExceptionPolicySpec;
import io.dscope.camel.agent.model.RetryPolicySpec;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.agent.runtime.ConversationArchiveService;
import io.dscope.camel.agent.runtime.ResolvedAgentPlan;
import io.dscope.camel.agent.runtime.RuntimePlaceholderResolver;
import io.dscope.camel.agent.util.A2UiPayloadSupport;

public class AgentAgUiPreRunTextProcessor implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentAgUiPreRunTextProcessor.class);

    private static final String DEFAULT_PROMPT = "Please help me.";
    private static final String DEFAULT_AGENT_ENDPOINT_URI = "agent:default?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}";
    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("\\b([1-5][0-9]{2})\\b");
    private static final String EXCEPTION_POLICY_SCOPE_AGUI_PRE_RUN = "agui.pre-run";

    private final MarkdownBlueprintLoader markdownBlueprintLoader;
    private final ObjectMapper objectMapper;
    private final Map<String, AgentBlueprint> blueprintCache;

    public AgentAgUiPreRunTextProcessor() {
        this.markdownBlueprintLoader = new MarkdownBlueprintLoader();
        this.objectMapper = new ObjectMapper();
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
        String sessionId = firstNonBlank(stringValue(params.get("sessionId")), threadId);
        String runId = firstNonBlank(stringValue(params.get("runId")), UUID.randomUUID().toString());
        String requestedPlanName = stringValue(params.get("planName"));
        String requestedPlanVersion = stringValue(params.get("planVersion"));
        String locale = resolveLocale(exchange, params);

        LOGGER.info("AGUI pre-run started: threadId={}, sessionId={}, runId={}, promptChars={}",
            threadId,
            sessionId,
            runId,
            prompt.length());

        ResolvedAgentPlan resolvedPlan = resolvePlan(exchange, threadId, requestedPlanName, requestedPlanVersion);
        AgentBlueprint blueprint = loadBlueprint(resolvedPlan);
        RuntimeConfig runtimeConfig = resolveRuntimeConfig(exchange, resolvedPlan);

        ProducerTemplate template = exchange.getContext().createProducerTemplate();
        String outputText;
        Map<String, Object> headers = new HashMap<>();
        headers.put(AgentHeaders.CONVERSATION_ID, threadId);
        headers.put(AgentHeaders.AGUI_SESSION_ID, sessionId);
        headers.put(AgentHeaders.AGUI_RUN_ID, runId);
        headers.put(AgentHeaders.AGUI_THREAD_ID, threadId);
        headers.put(AgentHeaders.LOCALE, locale);
        if (requestedPlanName != null && !requestedPlanName.isBlank()) {
            headers.put(AgentHeaders.PLAN_NAME, requestedPlanName);
        }
        if (requestedPlanVersion != null && !requestedPlanVersion.isBlank()) {
            headers.put(AgentHeaders.PLAN_VERSION, requestedPlanVersion);
        }

        try {
            outputText = invokePrimaryAgentWithPolicies(template, runtimeConfig, prompt, headers, threadId);
            LOGGER.debug("AGUI pre-run primary agent response: threadId={}, outputChars={}",
                threadId,
                outputText == null ? 0 : outputText.length());
            if (runtimeConfig.fallbackEnabled() && requiresFallback(outputText, runtimeConfig.fallbackErrorMarkers())) {
                LOGGER.info("AGUI pre-run fallback triggered: threadId={}, reason=output-match-or-empty", threadId);
                outputText = runDeterministicFallback(template, prompt, runtimeConfig);
            }
        } catch (RuntimeException runtimeFailure) {
            ExceptionDecision decision = resolveExceptionDecision(runtimeFailure, runtimeConfig.exceptionPolicies());
            if (runtimeFailure instanceof IllegalStateException terminated
                && terminated.getMessage() != null
                && terminated.getMessage().contains("terminated by chained exception policy")) {
                throw runtimeFailure;
            }
            if (runtimeConfig.fallbackEnabled() && shouldFallbackForPrimaryFailure(decision)) {
                LOGGER.warn("AGUI pre-run primary agent failed, using fallback: threadId={}, error={}",
                    threadId,
                    runtimeFailure.getMessage() == null ? runtimeFailure.getClass().getSimpleName() : runtimeFailure.getMessage());
                outputText = runDeterministicFallback(template, prompt, runtimeConfig);
            } else {
                LOGGER.info("AGUI pre-run primary failure bypassed deterministic fallback: threadId={}, reason={}",
                    threadId,
                    fallbackBypassReason(decision));
                if (decision.action() == ExceptionAction.TERMINATE) {
                    throw new IllegalStateException("AGUI pre-run terminated by exception policy", runtimeFailure);
                }
                throw runtimeFailure;
            }
        }

        params.put("text", outputText);
        params.put("runId", runId);
        params.put("sessionId", sessionId);
        params.put("threadId", threadId);
        params.put("locale", locale);
        if (!resolvedPlan.legacyMode()) {
            params.put("planName", resolvedPlan.planName());
            params.put("planVersion", resolvedPlan.planVersion());
        }
        attachStructuredUi(
            params,
            outputText,
            blueprint,
            resolvedPlan,
            locale,
            A2UiPayloadSupport.supportedCatalogIds(params.get("a2uiSupportedCatalogIds"))
        );
        exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

        ConversationArchiveService archiveService = exchange.getContext().getRegistry().findSingleByType(ConversationArchiveService.class);
        if (archiveService != null) {
            archiveService.appendAgUiTurn(threadId, prompt, outputText, sessionId, runId);
        }

        LOGGER.info("AGUI pre-run completed: threadId={}, outputChars={}",
            threadId,
            outputText == null ? 0 : outputText.length());
    }

    private ResolvedAgentPlan resolvePlan(Exchange exchange, String conversationId, String planName, String planVersion) {
        AgentPlanSelectionResolver resolver = exchange.getContext().getRegistry().findSingleByType(AgentPlanSelectionResolver.class);
        if (resolver == null) {
            return ResolvedAgentPlan.legacy(firstNonBlank(propertyOrNull(exchange, "agent.blueprint"), "classpath:agents/agent.md"));
        }
        return resolver.resolve(
            conversationId,
            blankToNull(planName),
            blankToNull(planVersion),
            propertyOrNull(exchange, "agent.agents-config"),
            propertyOrNull(exchange, "agent.blueprint")
        );
    }

    private RuntimeConfig resolveRuntimeConfig(Exchange exchange, ResolvedAgentPlan resolvedPlan) {
        AgentBlueprint blueprint = loadBlueprint(resolvedPlan);
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
            "support.ticket.manage"
        );

        String kbFallbackUri = firstNonBlank(
            agUiPreRunSpec == null ? null : agUiPreRunSpec.kbUri(),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.kb-uri"),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.kbUri"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.kb-uri"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.kbUri"),
            resolveToolInvokeUri(exchange, blueprint, kbToolName)
        );
        String ticketFallbackUri = firstNonBlank(
            agUiPreRunSpec == null ? null : agUiPreRunSpec.ticketUri(),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.ticket-uri"),
            propertyOrNull(exchange, "agent.runtime.agui.pre-run.fallback.ticketUri"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.ticket-uri"),
            propertyOrNull(exchange, "agent.agui.pre-run.fallback.ticketUri"),
            resolveToolInvokeUri(exchange, blueprint, ticketToolName)
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
            csvValues("ticket,open,create,update,close,status,submit,escalate")
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
            RuntimePlaceholderResolver.resolveRequiredExecutionTarget(exchange.getContext(), agentEndpointUri, "aguiPreRun.agentEndpointUri"),
            RuntimePlaceholderResolver.resolveRequiredExecutionTarget(exchange.getContext(), kbFallbackUri, "aguiPreRun.fallback.kbUri"),
            RuntimePlaceholderResolver.resolveRequiredExecutionTarget(exchange.getContext(), ticketFallbackUri, "aguiPreRun.fallback.ticketUri"),
            fallbackEnabled,
            ticketKeywords,
            fallbackErrorMarkers,
            scopedExceptionPolicies(blueprint)
        );
    }

    private List<ExceptionPolicySpec> scopedExceptionPolicies(AgentBlueprint blueprint) {
        if (blueprint == null || blueprint.exceptionPolicies() == null || blueprint.exceptionPolicies().isEmpty()) {
            return List.of();
        }
        List<ExceptionPolicySpec> scoped = new ArrayList<>();
        for (ExceptionPolicySpec policy : blueprint.exceptionPolicies()) {
            if (policy == null) {
                continue;
            }
            String scope = policy.scope();
            if (scope == null || scope.isBlank() || EXCEPTION_POLICY_SCOPE_AGUI_PRE_RUN.equalsIgnoreCase(scope.trim())) {
                scoped.add(policy);
            }
        }
        return scoped;
    }

    private AgentBlueprint loadBlueprint(ResolvedAgentPlan resolvedPlan) {
        String location = resolvedPlan == null ? "" : firstNonBlank(resolvedPlan.blueprint());
        if (location.isBlank()) {
            return null;
        }
        try {
            return blueprintCache.computeIfAbsent(location, markdownBlueprintLoader::load);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String resolveToolInvokeUri(Exchange exchange, AgentBlueprint blueprint, String toolName) {
        if (blueprint == null || blueprint.tools() == null || toolName == null || toolName.isBlank()) {
            return "";
        }
        for (ToolSpec tool : blueprint.tools()) {
            if (tool == null || !toolName.equals(tool.name())) {
                continue;
            }
            if (tool.endpointUri() != null && !tool.endpointUri().isBlank()) {
                return RuntimePlaceholderResolver.resolveRequiredExecutionTarget(exchange.getContext(), tool.endpointUri().trim(), "tools[].endpointUri");
            }
            if (tool.routeId() != null && !tool.routeId().isBlank()) {
                return "direct:" + RuntimePlaceholderResolver.resolveRequiredExecutionTarget(exchange.getContext(), tool.routeId().trim(), "tools[].routeId");
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

    private String invokePrimaryAgentWithPolicies(
        ProducerTemplate template,
        RuntimeConfig runtimeConfig,
        String prompt,
        Map<String, Object> headers,
        String threadId
    ) {
        int attempt = 0;
        while (true) {
            try {
                return template.requestBodyAndHeaders(runtimeConfig.agentEndpointUri(), prompt, headers, String.class);
            } catch (RuntimeException failure) {
                ExceptionDecision decision = resolveExceptionDecision(failure, runtimeConfig.exceptionPolicies());
                int maxRetries = Math.max(decision.maxRetries(), 0);
                if (decision.action() == ExceptionAction.RETRY && attempt < maxRetries) {
                    long delayMs = computeRetryDelay(decision, attempt);
                    LOGGER.warn(
                        "AGUI pre-run primary attempt failed, retrying: threadId={}, attempt={}, maxRetries={}, statusCode={}, category={}, delayMs={}, policy={}",
                        threadId,
                        attempt + 1,
                        maxRetries,
                        decision.statusCode(),
                        decision.category(),
                        delayMs,
                        decision.policyName());
                    sleepRetry(delayMs);
                    attempt++;
                    continue;
                }
                if (decision.action() == ExceptionAction.RETRY && decision.onRetryExhaustedAction() == ExceptionAction.TERMINATE) {
                    throw new IllegalStateException("AGUI pre-run primary endpoint terminated by chained exception policy", failure);
                }
                if (decision.action() == ExceptionAction.RETRY && decision.onRetryExhaustedAction() == ExceptionAction.RESOLVE) {
                    return resolveExceptionWithLlm(template, runtimeConfig, prompt, headers, failure, decision.onRetryExhaustedPrompt());
                }
                if (decision.action() == ExceptionAction.RETRY && decision.onRetryExhaustedAction() == ExceptionAction.RETHROW) {
                    throw failure;
                }
                if (decision.action() == ExceptionAction.RESOLVE) {
                    return resolveExceptionWithLlm(template, runtimeConfig, prompt, headers, failure, decision.prompt());
                }
                throw failure;
            }
        }
    }

    private String resolveExceptionWithLlm(ProducerTemplate template,
                                           RuntimeConfig runtimeConfig,
                                           String originalPrompt,
                                           Map<String, Object> headers,
                                           Throwable failure,
                                           String policyPrompt) {
        String prompt = buildResolutionPrompt(originalPrompt, failure, policyPrompt);
        LOGGER.info("AGUI pre-run exception resolution via LLM endpoint={}", runtimeConfig.agentEndpointUri());
        return template.requestBodyAndHeaders(runtimeConfig.agentEndpointUri(), prompt, headers, String.class);
    }

    private String buildResolutionPrompt(String originalPrompt, Throwable failure, String policyPrompt) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("The previous AGUI pre-run request failed. Resolve it for the user using current conversation context and history. ");
        prompt.append("Provide a direct actionable response and avoid unnecessary tool calls.\n\n");
        prompt.append("Original user prompt: ").append(originalPrompt == null ? "" : originalPrompt).append("\n");
        prompt.append("Error: ").append(rootCauseMessage(failure)).append("\n");
        if (policyPrompt != null && !policyPrompt.isBlank()) {
            prompt.append("\nAdditional instructions:\n").append(policyPrompt.trim()).append("\n");
        }
        return prompt.toString();
    }

    private String rootCauseMessage(Throwable failure) {
        Throwable root = failure;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root == null) {
            return "unknown";
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private boolean shouldFallbackForPrimaryFailure(ExceptionDecision decision) {
        return decision.fallbackAllowed();
    }

    private String fallbackBypassReason(ExceptionDecision decision) {
        if (decision.action() == ExceptionAction.TERMINATE) {
            return "exception-policy-terminate";
        }
        if (decision.action() == ExceptionAction.RESOLVE) {
            return "exception-policy-resolve";
        }
        if (decision.action() == ExceptionAction.RETHROW && decision.category() == ExceptionCategory.BUSINESS) {
            return "business-exception-rethrow";
        }
        return "fallback-disabled-or-non-fallbackable";
    }

    private ExceptionDecision resolveExceptionDecision(Throwable failure, List<ExceptionPolicySpec> policies) {
        Integer statusCode = extractHttpStatusCode(failure);
        ExceptionCategory category = classifyFailure(statusCode);
        ExceptionPolicySpec matched = matchPolicy(statusCode, category, policies);
        if (matched == null) {
            if (category == ExceptionCategory.BUSINESS) {
                return new ExceptionDecision(ExceptionAction.RETHROW, ExceptionAction.RETHROW, category, statusCode, 0, 0L, false, 2.0d, 0L, "default-business", null, null);
            }
            return new ExceptionDecision(ExceptionAction.RETRY, ExceptionAction.RETHROW, category, statusCode, 0, 0L, true, 2.0d, 0L, "default-technical", null, null);
        }

        RetryPolicySpec retry = matched.retry();
        int maxRetries = retry == null || retry.maxRetries() == null ? 0 : Math.max(retry.maxRetries(), 0);
        long intervalMs = retry == null || retry.intervalMs() == null ? 0L : Math.max(retry.intervalMs(), 0L);
        boolean fallbackAllowed = matched.action() == ExceptionAction.RETRY;
        boolean exponentialBackoff = retry != null && Boolean.TRUE.equals(retry.exponentialBackoff());
        Double configuredMultiplier = retry == null ? null : retry.multiplier();
        double multiplier = configuredMultiplier == null || configuredMultiplier <= 1.0d ? 2.0d : configuredMultiplier;
        long maxIntervalMs = retry == null || retry.maxIntervalMs() == null ? 0L : Math.max(retry.maxIntervalMs(), 0L);
        ExceptionPolicySpec exhaustedPolicy = matched.action() == ExceptionAction.RETRY
            ? chainedRetryExhaustedPolicy(statusCode, category, policies, matched)
            : matched;
        ExceptionAction exhaustedAction = exhaustedPolicy == null || exhaustedPolicy.action() == null
            ? ExceptionAction.RETHROW
            : exhaustedPolicy.action();
        String exhaustedPrompt = exhaustedPolicy == null ? null : exhaustedPolicy.prompt();

        return new ExceptionDecision(
            matched.action(),
            exhaustedAction,
            category,
            statusCode,
            maxRetries,
            intervalMs,
            fallbackAllowed,
            exponentialBackoff ? multiplier : 1.0d,
            maxIntervalMs,
            matched.name() == null || matched.name().isBlank() ? "configured-policy" : matched.name(),
            matched.prompt(),
            exhaustedPrompt
        );
    }

    private ExceptionCategory classifyFailure(Integer statusCode) {
        if (statusCode == null) {
            return ExceptionCategory.TECHNICAL;
        }
        if (statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500) {
            return ExceptionCategory.TECHNICAL;
        }
        if (statusCode >= 400 && statusCode < 500) {
            return ExceptionCategory.BUSINESS;
        }
        return ExceptionCategory.TECHNICAL;
    }

    private ExceptionPolicySpec matchPolicy(Integer statusCode, ExceptionCategory category, List<ExceptionPolicySpec> policies) {
        if (policies == null || policies.isEmpty()) {
            return null;
        }
        for (ExceptionPolicySpec policy : policies) {
            if (policy == null || policy.action() == null) {
                continue;
            }
            if (policy.category() != null && policy.category() != category) {
                continue;
            }
            if (policy.httpStatusCodes() != null && !policy.httpStatusCodes().isEmpty()) {
                if (statusCode == null || !policy.httpStatusCodes().contains(statusCode)) {
                    continue;
                }
            }
            return policy;
        }
        return null;
    }

    private ExceptionPolicySpec chainedRetryExhaustedPolicy(Integer statusCode,
                                                            ExceptionCategory category,
                                                            List<ExceptionPolicySpec> policies,
                                                            ExceptionPolicySpec matchedRetryPolicy) {
        if (policies == null || policies.isEmpty() || matchedRetryPolicy == null) {
            return null;
        }
        int startIndex = policies.indexOf(matchedRetryPolicy);
        if (startIndex < 0) {
            return null;
        }
        for (int i = startIndex + 1; i < policies.size(); i++) {
            ExceptionPolicySpec candidate = policies.get(i);
            if (!matchesPolicy(statusCode, category, candidate)) {
                continue;
            }
            if (candidate.action() != null && candidate.action() != ExceptionAction.RETRY) {
                return candidate;
            }
        }
        return null;
    }

    private boolean matchesPolicy(Integer statusCode, ExceptionCategory category, ExceptionPolicySpec policy) {
        if (policy == null || policy.action() == null) {
            return false;
        }
        if (policy.category() != null && policy.category() != category) {
            return false;
        }
        if (policy.httpStatusCodes() != null && !policy.httpStatusCodes().isEmpty()) {
            return statusCode != null && policy.httpStatusCodes().contains(statusCode);
        }
        return true;
    }

    private long computeRetryDelay(ExceptionDecision decision, int attempt) {
        long initialDelay = Math.max(decision.intervalMs(), 0L);
        if (initialDelay <= 0L) {
            return 0L;
        }
        if (decision.multiplier() <= 1.0d) {
            return initialDelay;
        }
        double computed = initialDelay * Math.pow(decision.multiplier(), Math.max(0, attempt));
        long delay = computed > Long.MAX_VALUE ? Long.MAX_VALUE : (long) computed;
        if (decision.maxIntervalMs() > 0L) {
            delay = Math.min(delay, decision.maxIntervalMs());
        }
        return Math.max(delay, 0L);
    }

    private void sleepRetry(long delayMs) {
        if (delayMs <= 0L) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(delayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry AGUI pre-run primary endpoint", interruptedException);
        }
    }

    private Integer extractHttpStatusCode(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            Integer reflected = reflectedStatusCode(cursor);
            if (reflected != null) {
                return reflected;
            }
            Integer parsed = parsedStatusCode(cursor.getMessage());
            if (parsed != null) {
                return parsed;
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private Integer reflectedStatusCode(Throwable failure) {
        try {
            java.lang.reflect.Method method = failure.getClass().getMethod("getStatusCode");
            Object status = method.invoke(failure);
            if (status instanceof Number number) {
                return number.intValue();
            }
        } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private Integer parsedStatusCode(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = HTTP_STATUS_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String runDeterministicFallback(ProducerTemplate template, String prompt, RuntimeConfig runtimeConfig) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("query", prompt);
        if (isTicketPrompt(prompt, runtimeConfig.ticketKeywords())) {
            String ticketUri = requireFallbackUri(
                runtimeConfig.ticketFallbackUri(),
                "ticket",
                "aguiPreRun.fallback.ticketUri",
                "aguiPreRun.fallback.ticketToolName",
                "agent.runtime.agui.pre-run.fallback.ticket-uri"
            );
            LOGGER.info("AGUI pre-run deterministic fallback route=ticket: uri={}", ticketUri);
            return template.requestBody(ticketUri, payload, String.class);
        }
        String kbUri = requireFallbackUri(
            runtimeConfig.kbFallbackUri(),
            "knowledge",
            "aguiPreRun.fallback.kbUri",
            "aguiPreRun.fallback.kbToolName",
            "agent.runtime.agui.pre-run.fallback.kb-uri"
        );
        LOGGER.info("AGUI pre-run deterministic fallback route=kb: uri={}", kbUri);
        return template.requestBody(kbUri, payload, String.class);
    }

    private String requireFallbackUri(String uri, String fallbackType, String blueprintUriField, String blueprintToolField, String runtimeProperty) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException(
                "AGUI deterministic " + fallbackType + " fallback was selected, but no fallback route is configured. "
                    + "Set " + blueprintUriField + ", " + blueprintToolField + ", or " + runtimeProperty + "."
            );
        }
        return uri;
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

    private void attachStructuredUi(Map<String, Object> params,
                                    String outputText,
                                    AgentBlueprint blueprint,
                                    ResolvedAgentPlan resolvedPlan,
                                    String locale,
                                    List<String> supportedCatalogIds) {
        params.remove("widget");
        params.remove("a2ui");
        JsonNode parsed = parseJson(outputText);
        if (parsed == null || parsed.isNull() || !parsed.isObject()) {
            return;
        }
        ObjectNode widget = A2UiPayloadSupport.buildWidget(objectMapper, blueprint, parsed, supportedCatalogIds);
        if (widget != null && !widget.isEmpty()) {
            params.put("widget", objectMapper.convertValue(widget, Map.class));
        }
        ObjectNode a2ui = A2UiPayloadSupport.buildPayload(objectMapper, blueprint, parsed, resolvedPlan, locale, supportedCatalogIds);
        if (a2ui != null && !a2ui.isEmpty()) {
            params.put("a2ui", objectMapper.convertValue(a2ui, Map.class));
        }
    }

    private String resolveLocale(Exchange exchange, Map<String, Object> params) {
        return A2UiPayloadSupport.resolveLocale(
            stringValue(params.get("locale")),
            exchange.getMessage().getHeader(AgentHeaders.LOCALE, String.class),
            exchange.getMessage().getHeader("Accept-Language", String.class),
            propertyOrNull(exchange, "agent.locale")
        );
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (RuntimeException | IOException parseFailure) {
            return null;
        }
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
    private List<String> firstNonEmpty(List<String>... options) {
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
        List<String> fallbackErrorMarkers,
        List<ExceptionPolicySpec> exceptionPolicies
    ) {
    }

    private record ExceptionDecision(
        ExceptionAction action,
        ExceptionAction onRetryExhaustedAction,
        ExceptionCategory category,
        Integer statusCode,
        int maxRetries,
        long intervalMs,
        boolean fallbackAllowed,
        double multiplier,
        long maxIntervalMs,
        String policyName,
        String prompt,
        String onRetryExhaustedPrompt
    ) {
    }
}
