package io.dscope.camel.agent.executor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dscope.camel.agent.a2a.A2AToolClient;
import io.dscope.camel.agent.a2a.A2AToolContext;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.model.ExceptionAction;
import io.dscope.camel.agent.model.ExceptionCategory;
import io.dscope.camel.agent.model.ExceptionPolicySpec;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.RetryPolicySpec;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.runtime.RuntimePlaceholderResolver;
import io.dscope.camel.mcp.McpClient;

public class CamelToolExecutor implements ToolExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CamelToolExecutor.class);
    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("\\b([1-5][0-9]{2})\\b");
    private static final String EXCEPTION_POLICY_SCOPE_TOOL_EXECUTE = "tool.execute";
    private static final String DEFAULT_EXCEPTION_RESOLUTION_ENDPOINT_URI = "agent:default?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}";

    private final CamelContext camelContext;
    private final ProducerTemplate producerTemplate;
    private final ObjectMapper objectMapper;
    private final A2AToolClient a2aToolClient;

    public CamelToolExecutor(ProducerTemplate producerTemplate, ObjectMapper objectMapper) {
        this(null, producerTemplate, objectMapper, null, A2AToolContext.EMPTY);
    }

    public CamelToolExecutor(CamelContext camelContext,
                             ProducerTemplate producerTemplate,
                             ObjectMapper objectMapper,
                             PersistenceFacade persistenceFacade,
                             A2AToolContext a2aToolContext) {
        this.camelContext = camelContext;
        this.producerTemplate = producerTemplate;
        this.objectMapper = objectMapper;
        this.a2aToolClient = new A2AToolClient(camelContext, objectMapper, persistenceFacade, a2aToolContext);
    }

    @Override
    public ToolResult execute(ToolSpec toolSpec, JsonNode arguments, ExecutionContext context) {
        String target = target(toolSpec);
        if (isA2ATarget(target)) {
            return a2aToolClient.execute(target, toolSpec, arguments, context);
        }
        Map<String, Object> headers = new HashMap<>();
        headers.put(AgentHeaders.CONVERSATION_ID, context.conversationId());
        headers.put(AgentHeaders.TASK_ID, context.taskId());
        headers.put(AgentHeaders.TOOL_NAME, toolSpec.name());
        headers.put(AgentHeaders.TRACE_ID, context.traceId());

        Object response = invokeWithPolicies(toolSpec, arguments, context, target, headers);
        JsonNode data = objectMapper.valueToTree(response);
        String content = data.isValueNode() ? data.asText() : data.toPrettyString();
        return new ToolResult(content, data, List.of());
    }

    private Object invokeWithPolicies(ToolSpec toolSpec,
                                      JsonNode arguments,
                                      ExecutionContext context,
                                      String target,
                                      Map<String, Object> headers) {
        int attempt = 0;
        while (true) {
            try {
                return invokeTarget(toolSpec, arguments, context, target, headers);
            } catch (RuntimeException failure) {
                ExceptionDecision decision = resolveExceptionDecision(failure, scopedExceptionPolicies(context));
                int maxRetries = Math.max(decision.maxRetries(), 0);
                if (decision.action() == ExceptionAction.RETRY && attempt < maxRetries) {
                    long delayMs = computeRetryDelay(decision, attempt);
                    LOGGER.warn(
                        "Tool invoke failed, retrying: conversationId={}, taskId={}, tool={}, target={}, attempt={}, maxRetries={}, statusCode={}, category={}, delayMs={}, policy={}",
                        context.conversationId(),
                        context.taskId(),
                        toolSpec.name(),
                        target,
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
                    throw new IllegalStateException("Tool execution terminated by chained exception policy", failure);
                }

                if (decision.action() == ExceptionAction.RETRY && decision.onRetryExhaustedAction() == ExceptionAction.RESOLVE) {
                    return resolveExceptionWithLlm(toolSpec, context, headers, target, arguments, failure, decision.onRetryExhaustedPrompt());
                }

                if (decision.action() == ExceptionAction.RETRY && decision.onRetryExhaustedAction() == ExceptionAction.RETHROW) {
                    throw failure;
                }

                if (decision.action() == ExceptionAction.RESOLVE) {
                    return resolveExceptionWithLlm(toolSpec, context, headers, target, arguments, failure, decision.prompt());
                }

                if (decision.action() == ExceptionAction.TERMINATE) {
                    throw new IllegalStateException("Tool execution terminated by exception policy", failure);
                }
                throw failure;
            }
        }
    }

    private Object resolveExceptionWithLlm(ToolSpec toolSpec,
                                           ExecutionContext context,
                                           Map<String, Object> headers,
                                           String target,
                                           JsonNode arguments,
                                           Throwable failure,
                                           String policyPrompt) {
        String endpointUri = resolveExceptionResolutionEndpointUri();
        String resolverPrompt = buildResolutionPrompt(policyPrompt, toolSpec, target, arguments, failure);
        LOGGER.info("Tool exception resolution via LLM: conversationId={}, taskId={}, tool={}, endpoint={}",
            context.conversationId(),
            context.taskId(),
            toolSpec.name(),
            endpointUri);
        return producerTemplate.requestBodyAndHeaders(endpointUri, resolverPrompt, headers, String.class);
    }

    private Object invokeTarget(ToolSpec toolSpec,
                                JsonNode arguments,
                                ExecutionContext context,
                                String target,
                                Map<String, Object> headers) {
        if (isMcpTarget(target)) {
            Object requestArguments = requestBody(arguments);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolSpec.name());
            params.put("arguments", requestArguments);
            LOGGER.debug("MCP tool invoke started: conversationId={}, taskId={}, tool={}, endpoint={}, argumentShape={}",
                context.conversationId(),
                context.taskId(),
                toolSpec.name(),
                target,
                argumentShape(requestArguments));
            try {
                Object response = McpClient.callResultJson(producerTemplate, target, "tools/call", params);
                LOGGER.debug("MCP tool invoke completed: conversationId={}, taskId={}, tool={}, endpoint={}, responseShape={}",
                    context.conversationId(),
                    context.taskId(),
                    toolSpec.name(),
                    target,
                    payloadShape(response));
                return response;
            } catch (RuntimeException failure) {
                Throwable root = failure;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                String rootMessage = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
                LOGGER.warn("MCP tool invoke failed: conversationId={}, taskId={}, tool={}, endpoint={}, reason={}",
                    context.conversationId(),
                    context.taskId(),
                    toolSpec.name(),
                    target,
                    rootMessage);
                LOGGER.debug("MCP tool invoke failure details", failure);
                throw failure;
            }
        }
        return producerTemplate.requestBodyAndHeaders(target, requestBody(arguments), headers);
    }

    private List<ExceptionPolicySpec> scopedExceptionPolicies(ExecutionContext context) {
        if (context == null || context.exceptionPolicies() == null || context.exceptionPolicies().isEmpty()) {
            return List.of();
        }
        return context.exceptionPolicies().stream()
            .filter(policy -> {
                if (policy == null) {
                    return false;
                }
                String scope = policy.scope();
                return scope == null || scope.isBlank() || EXCEPTION_POLICY_SCOPE_TOOL_EXECUTE.equalsIgnoreCase(scope.trim());
            })
            .toList();
    }

    private ExceptionDecision resolveExceptionDecision(Throwable failure, List<ExceptionPolicySpec> policies) {
        Integer statusCode = extractHttpStatusCode(failure);
        ExceptionCategory category = classifyFailure(statusCode);
        ExceptionPolicySpec matched = matchPolicy(statusCode, category, policies);
        if (matched == null) {
            if (category == ExceptionCategory.BUSINESS) {
                return new ExceptionDecision(ExceptionAction.RETHROW, ExceptionAction.RETHROW, category, statusCode, 0, 0L, 1.0d, 0L, "default-business", null, null);
            }
            return new ExceptionDecision(ExceptionAction.RETHROW, ExceptionAction.RETHROW, category, statusCode, 0, 0L, 1.0d, 0L, "default-technical", null, null);
        }

        RetryPolicySpec retry = matched.retry();
        int maxRetries = retry == null || retry.maxRetries() == null ? 0 : Math.max(retry.maxRetries(), 0);
        long intervalMs = retry == null || retry.intervalMs() == null ? 0L : Math.max(retry.intervalMs(), 0L);
        Double configuredMultiplier = retry == null ? null : retry.multiplier();
        double multiplier = configuredMultiplier == null || configuredMultiplier <= 1.0d ? 2.0d : configuredMultiplier;
        if (retry == null || !Boolean.TRUE.equals(retry.exponentialBackoff())) {
            multiplier = 1.0d;
        }
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
            multiplier,
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
            throw new IllegalStateException("Interrupted while waiting to retry tool execution", interruptedException);
        }
    }

    private String resolveExceptionResolutionEndpointUri() {
        String configured = "";
        if (camelContext != null) {
            try {
                configured = camelContext.resolvePropertyPlaceholders("{{agent.runtime.exception-policy.resolve.endpoint-uri:}}");
            } catch (RuntimeException ignored) {
                configured = "";
            }
        }
        String raw = configured == null || configured.isBlank() ? DEFAULT_EXCEPTION_RESOLUTION_ENDPOINT_URI : configured;
        return RuntimePlaceholderResolver.resolveRequiredExecutionTarget(camelContext, raw, "exceptionPolicy.resolve.endpointUri");
    }

    private String buildResolutionPrompt(String policyPrompt,
                                         ToolSpec toolSpec,
                                         String target,
                                         JsonNode arguments,
                                         Throwable failure) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("A tool execution failed. Resolve it for the user using current conversation context and history. ");
        prompt.append("Do not call tools again unless strictly required. Prefer a direct actionable answer.\n\n");
        prompt.append("Tool: ").append(toolSpec == null ? "" : toolSpec.name()).append("\n");
        prompt.append("Target: ").append(target == null ? "" : target).append("\n");
        prompt.append("Arguments: ").append(arguments == null ? "null" : arguments.toString()).append("\n");
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

    private Object requestBody(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return null;
        }
        if (arguments.isValueNode()) {
            return arguments.asText();
        }
        return objectMapper.convertValue(arguments, Object.class);
    }

    private String target(ToolSpec toolSpec) {
        if (toolSpec.routeId() != null && !toolSpec.routeId().isBlank()) {
            return "direct:" + RuntimePlaceholderResolver.resolveRequiredExecutionTarget(camelContext, toolSpec.routeId(), "tools[].routeId");
        }
        if (toolSpec.endpointUri() != null && !toolSpec.endpointUri().isBlank()) {
            return RuntimePlaceholderResolver.resolveRequiredExecutionTarget(camelContext, toolSpec.endpointUri(), "tools[].endpointUri");
        }
        throw new IllegalArgumentException("Tool target is missing routeId/endpointUri: " + toolSpec.name());
    }

    private boolean isMcpTarget(String target) {
        return target != null && target.startsWith("mcp:");
    }

    private boolean isA2ATarget(String target) {
        return target != null && target.startsWith("a2a:");
    }

    private String argumentShape(Object arguments) {
        if (arguments == null) {
            return "null";
        }
        if (arguments instanceof Map<?, ?> map) {
            return "map(keys=" + map.keySet() + ")";
        }
        if (arguments instanceof List<?> list) {
            return "list(size=" + list.size() + ")";
        }
        return arguments.getClass().getSimpleName();
    }

    private String payloadShape(Object payload) {
        if (payload == null) {
            return "null";
        }
        if (payload instanceof Map<?, ?> map) {
            return "map(keys=" + map.keySet() + ")";
        }
        if (payload instanceof List<?> list) {
            return "list(size=" + list.size() + ")";
        }
        return payload.getClass().getSimpleName();
    }

    private record ExceptionDecision(
        ExceptionAction action,
        ExceptionAction onRetryExhaustedAction,
        ExceptionCategory category,
        Integer statusCode,
        int maxRetries,
        long intervalMs,
        double multiplier,
        long maxIntervalMs,
        String policyName,
        String prompt,
        String onRetryExhaustedPrompt
    ) {
    }
}
