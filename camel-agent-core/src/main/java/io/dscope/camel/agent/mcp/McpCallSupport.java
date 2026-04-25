package io.dscope.camel.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.slf4j.Logger;

public final class McpCallSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> REDACTED_FIELD_NAMES = Set.of(
        "authorization", "apiKey", "apikey", "token", "accessToken", "refreshToken",
        "email", "phone", "attendees", "fileUrl"
    );
    private static final Set<Integer> DEFAULT_RETRIABLE_STATUS_CODES = Set.of(404, 408, 429, 502, 503, 504);
    private static final int LOG_SNIPPET_LIMIT = 600;
    private static final String RETRY_MAX_ATTEMPTS_KEY = "camel.agent.mcp.retry.max-attempts";
    private static final String RETRY_DELAY_MS_KEY = "camel.agent.mcp.retry.delay-ms";
    private static final String RETRY_STATUS_CODES_KEY = "camel.agent.mcp.retry.status-codes";

    private McpCallSupport() {
    }

    public static <T> T invoke(Logger logger,
                               CamelContext camelContext,
                               String operation,
                               String endpoint,
                               String toolName,
                               String conversationId,
                               String taskId,
                               Object requestPayload,
                               Supplier<T> action) {
        McpCallSettings settings = resolveSettings(camelContext);
        String payloadShape = payloadShape(requestPayload);
        String payloadSnippet = toLogSnippet(requestPayload);
        int attempts = settings.maxAttempts();

        for (int attempt = 1; attempt <= attempts; attempt++) {
            long startedAt = System.currentTimeMillis();
            logger.debug("MCP {} started: conversationId={}, taskId={}, tool={}, endpoint={}, attempt={}, maxAttempts={}, requestShape={}, requestSnippet={}",
                operation,
                conversationId,
                taskId,
                toolName,
                endpoint,
                attempt,
                attempts,
                payloadShape,
                payloadSnippet);
            try {
                T response = action.get();
                logger.debug("MCP {} completed: conversationId={}, taskId={}, tool={}, endpoint={}, attempt={}, durationMs={}, responseShape={}, responseSnippet={}",
                    operation,
                    conversationId,
                    taskId,
                    toolName,
                    endpoint,
                    attempt,
                    System.currentTimeMillis() - startedAt,
                    payloadShape(response),
                    toLogSnippet(response));
                return response;
            } catch (RuntimeException failure) {
                HttpOperationFailedException httpFailure = findHttpFailure(failure);
                Throwable root = findRootCause(failure);
                String reason = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
                Integer statusCode = httpFailure == null ? null : httpFailure.getStatusCode();
                boolean retriable = httpFailure != null
                    && settings.retriableStatusCodes().contains(statusCode)
                    && attempt < attempts;
                if (retriable) {
                    logger.warn("MCP {} retry scheduled: conversationId={}, taskId={}, tool={}, endpoint={}, attempt={}, maxAttempts={}, statusCode={}, durationMs={}, reason={}, requestSnippet={}, responseSnippet={}",
                        operation,
                        conversationId,
                        taskId,
                        toolName,
                        endpoint,
                        attempt,
                        attempts,
                        statusCode,
                        System.currentTimeMillis() - startedAt,
                        reason,
                        payloadSnippet,
                        toLogSnippet(httpFailure.getResponseBody()));
                    sleepQuietly(settings.retryDelayMs());
                    continue;
                }

                logger.warn("MCP {} failed: conversationId={}, taskId={}, tool={}, endpoint={}, attempt={}, maxAttempts={}, statusCode={}, durationMs={}, reason={}, requestSnippet={}, responseSnippet={}",
                    operation,
                    conversationId,
                    taskId,
                    toolName,
                    endpoint,
                    attempt,
                    attempts,
                    statusCode,
                    System.currentTimeMillis() - startedAt,
                    reason,
                    payloadSnippet,
                    httpFailure == null ? "" : toLogSnippet(httpFailure.getResponseBody()));
                logger.debug("MCP {} failure details", operation, failure);
                throw failure;
            }
        }

        throw new IllegalStateException("Unreachable MCP call flow");
    }

    static McpCallSettings resolveSettings(CamelContext camelContext) {
        int maxAttempts = parseInt(resolveProperty(camelContext, RETRY_MAX_ATTEMPTS_KEY, "3"), 3);
        long retryDelayMs = parseLong(resolveProperty(camelContext, RETRY_DELAY_MS_KEY, "250"), 250L);
        Set<Integer> retriableStatusCodes = parseStatusCodes(resolveProperty(
            camelContext,
            RETRY_STATUS_CODES_KEY,
            DEFAULT_RETRIABLE_STATUS_CODES.stream().sorted().map(String::valueOf).collect(Collectors.joining(","))
        ));
        return new McpCallSettings(Math.max(1, maxAttempts), Math.max(0L, retryDelayMs), retriableStatusCodes);
    }

    static Throwable findRootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    static HttpOperationFailedException findHttpFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof HttpOperationFailedException httpFailure) {
                return httpFailure;
            }
            if (current instanceof CamelExecutionException camelExecution && camelExecution.getCause() != null) {
                current = camelExecution.getCause();
                continue;
            }
            current = current.getCause();
        }
        return null;
    }

    static String payloadShape(Object payload) {
        if (payload == null) {
            return "null";
        }
        if (payload instanceof Map<?, ?> map) {
            return "map(keys=" + map.keySet() + ")";
        }
        if (payload instanceof Iterable<?> iterable) {
            int size = 0;
            Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                iterator.next();
                size++;
            }
            return "list(size=" + size + ")";
        }
        return payload.getClass().getSimpleName();
    }

    static String toLogSnippet(Object value) {
        if (value == null) {
            return "";
        }

        String text = sanitizeForLogs(String.valueOf(value))
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .trim();
        if (text.length() <= LOG_SNIPPET_LIMIT) {
            return text;
        }
        return text.substring(0, LOG_SNIPPET_LIMIT) + "...";
    }

    private static String sanitizeForLogs(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(text);
            redactNode(root);
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException ignored) {
            return text;
        }
    }

    private static void redactNode(JsonNode node) {
        if (node == null) {
            return;
        }

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            objectNode.fieldNames().forEachRemaining(fieldName -> {
                JsonNode child = objectNode.get(fieldName);
                if (shouldRedact(fieldName)) {
                    objectNode.put(fieldName, "[REDACTED]");
                } else {
                    redactNode(child);
                }
            });
            return;
        }

        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (JsonNode child : arrayNode) {
                redactNode(child);
            }
        }
    }

    private static boolean shouldRedact(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String normalized = fieldName.trim();
        return REDACTED_FIELD_NAMES.contains(normalized)
            || REDACTED_FIELD_NAMES.contains(normalized.toLowerCase());
    }

    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(Math.max(0L, delayMs));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String resolveProperty(CamelContext camelContext, String key, String defaultValue) {
        if (camelContext != null) {
            try {
                String resolved = camelContext.resolvePropertyPlaceholders("{{" + key + ":" + defaultValue + "}}");
                if (resolved != null && !resolved.isBlank()) {
                    return resolved;
                }
            } catch (Exception ignored) {
                // Fall back to environment/system properties below.
            }
        }
        String environmentKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        String environmentValue = System.getenv(environmentKey);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }
        String systemValue = System.getProperty(key);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        return defaultValue;
    }

    private static int parseInt(String rawValue, int defaultValue) {
        try {
            return Integer.parseInt(rawValue);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long parseLong(String rawValue, long defaultValue) {
        try {
            return Long.parseLong(rawValue);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static Set<Integer> parseStatusCodes(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DEFAULT_RETRIABLE_STATUS_CODES;
        }
        Set<Integer> parsed = Arrays.stream(rawValue.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(rawCode -> {
                try {
                    return Integer.valueOf(rawCode);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            })
            .filter(value -> value != null)
            .collect(Collectors.toSet());
        return parsed.isEmpty() ? DEFAULT_RETRIABLE_STATUS_CODES : Set.copyOf(parsed);
    }

    record McpCallSettings(int maxAttempts, long retryDelayMs, Set<Integer> retriableStatusCodes) {
    }
}