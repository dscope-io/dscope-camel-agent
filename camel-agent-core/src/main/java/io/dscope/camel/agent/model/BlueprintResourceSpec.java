package io.dscope.camel.agent.model;

import java.util.List;

public record BlueprintResourceSpec(
    String name,
    String uri,
    String kind,
    String format,
    List<String> includeIn,
    String loadPolicy,
    String refreshPolicy,
    boolean optional,
    long maxBytes
) {

    public BlueprintResourceSpec {
        includeIn = includeIn == null ? List.of("chat") : List.copyOf(includeIn);
        loadPolicy = blankToDefault(loadPolicy, "startup");
        kind = blankToDefault(kind, "document");
        format = blankToDefault(format, inferFormat(uri));
        maxBytes = maxBytes <= 0 ? 262_144L : maxBytes;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String inferFormat(String uri) {
        if (uri == null || uri.isBlank()) {
            return "text";
        }
        String lower = uri.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "markdown";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "html";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        if (lower.endsWith(".pdf")) {
            return "pdf";
        }
        return "text";
    }
}