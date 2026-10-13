package io.dscope.camel.agent.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.util.Set;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/** Guards management and credential-minting HTTP paths with a deployment-provided bearer token. */
public final class AgentHttpAccessProcessor implements Processor {

    public static final String BEAN_NAME = "agentHttpAccessProcessor";

    private static final Set<String> PROTECTED_PREFIXES = Set.of(
        "/audit/", "/runtime/", "/mcp/admin", "/telephony/", "/sample/agent/", "/realtime/session/", "/voice/session/",
        "/sip/adapter/", "/twilio/adapter/"
    );

    @Override
    public void process(Exchange exchange) {
        String path = requestPath(exchange);
        if (!isProtected(path)) {
            return;
        }
        String expected = property(exchange, "agent.security.access-token");
        if (expected == null || expected.isBlank()) {
            deny(exchange, 503, "Sensitive HTTP access is not configured");
            return;
        }
        String supplied = bearerToken(exchange.getMessage().getHeader("Authorization", String.class));
        if (supplied == null) {
            supplied = exchange.getMessage().getHeader("X-Agent-Access-Token", String.class);
        }
        if (supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            deny(exchange, 401, "Unauthorized");
        }
    }

    private static boolean isProtected(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return PROTECTED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static String requestPath(Exchange exchange) {
        String path = exchange.getMessage().getHeader(Exchange.HTTP_PATH, String.class);
        if (path != null && !path.isBlank()) {
            return path;
        }
        String uri = exchange.getMessage().getHeader(Exchange.HTTP_URI, String.class);
        if (uri == null || uri.isBlank()) {
            return path;
        }
        try {
            return URI.create(uri).getPath();
        } catch (IllegalArgumentException ignored) {
            return uri;
        }
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isBlank() ? null : token;
    }

    private static String property(Exchange exchange, String key) {
        try {
            String value = exchange.getContext().resolvePropertyPlaceholders("{{" + key + "}}");
            return value == null || value.contains("{{") ? null : value.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void deny(Exchange exchange, int status, String message) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody("{\"error\":\"" + message + "\"}");
        exchange.setRouteStop(true);
    }
}
