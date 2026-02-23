package io.dscope.camel.agent.model;

import java.util.Locale;

public enum AuditGranularity {
    NONE,
    INFO,
    ERROR,
    DEBUG;

    public static AuditGranularity from(String value) {
        if (value == null || value.isBlank()) {
            return INFO;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> NONE;
            case "info" -> INFO;
            case "error" -> ERROR;
            case "debug" -> DEBUG;
            default -> INFO;
        };
    }
}
