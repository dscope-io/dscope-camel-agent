package io.dscope.camel.agent.model;

import java.util.Locale;

public record ResolvedBlueprintResource(
    BlueprintResourceSpec spec,
    String resolvedUri,
    String contentType,
    long sizeBytes,
    String text
) {

    public boolean includedIn(String target) {
        if (target == null || target.isBlank() || spec == null || spec.includeIn() == null) {
            return false;
        }
        String normalized = target.trim().toLowerCase(Locale.ROOT);
        return spec.includeIn().stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.equals(normalized) || value.equals("both"));
    }
}