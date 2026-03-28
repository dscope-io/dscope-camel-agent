package io.dscope.camel.agent.springai;

import io.dscope.camel.agent.model.ToolSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

final class OpenAiPromptCacheSupport {

    private OpenAiPromptCacheSupport() {
    }

    static PromptCacheSettings resolve(Properties properties,
                                       String model,
                                       String systemPrompt,
                                       List<ToolSpec> tools) {
        boolean enabled = booleanProp(properties,
            "agent.runtime.spring-ai.openai.prompt-cache.enabled",
            booleanProp(properties, "spring.ai.openai.prompt-cache.enabled", false));
        String key = firstNonBlank(
            property(properties, "agent.runtime.spring-ai.openai.prompt-cache.key"),
            property(properties, "spring.ai.openai.prompt-cache.key")
        );
        String retention = normalizeRetention(firstNonBlank(
            property(properties, "agent.runtime.spring-ai.openai.prompt-cache.retention"),
            property(properties, "spring.ai.openai.prompt-cache.retention")
        ));

        if (!enabled && key == null && retention == null) {
            return PromptCacheSettings.disabled();
        }

        String resolvedKey = key == null ? defaultKey(model, systemPrompt, tools) : key.trim();
        return new PromptCacheSettings(resolvedKey, retention);
    }

    private static String defaultKey(String model, String systemPrompt, List<ToolSpec> tools) {
        StringBuilder source = new StringBuilder();
        source.append(model == null ? "" : model.trim()).append('\n');
        source.append(systemPrompt == null ? "" : systemPrompt.trim()).append('\n');
        if (tools != null) {
            for (ToolSpec tool : tools) {
                if (tool == null) {
                    continue;
                }
                source.append(tool.name() == null ? "" : tool.name().trim()).append('|');
                source.append(tool.description() == null ? "" : tool.description().trim()).append('|');
                source.append(tool.inputSchema() == null ? "" : tool.inputSchema().toString()).append('\n');
            }
        }
        return "camel-agent-openai-cache-v1-" + sha256Hex(source.toString()).substring(0, 16);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                hex.append(Character.forDigit((current >> 4) & 0xF, 16));
                hex.append(Character.forDigit(current & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private static String normalizeRetention(String retention) {
        if (retention == null || retention.isBlank()) {
            return null;
        }
        String normalized = retention.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "24h", "24-hour", "24_hour", "24hours" -> "24h";
            case "in-memory", "in_memory", "memory", "inmemory" -> "in-memory";
            default -> null;
        };
    }

    private static boolean booleanProp(Properties properties, String key, boolean defaultValue) {
        if (properties == null) {
            return defaultValue;
        }
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static String property(Properties properties, String key) {
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    record PromptCacheSettings(String key, String retention) {

        static PromptCacheSettings disabled() {
            return new PromptCacheSettings(null, null);
        }

        boolean enabled() {
            return key != null && !key.isBlank();
        }
    }
}