package io.dscope.camel.agent.runtime;

import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RuntimePropertyPlaceholderResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    private RuntimePropertyPlaceholderResolver() {
    }

    static Properties resolve(Properties source) {
        Properties resolved = new Properties();
        if (source == null) {
            return resolved;
        }
        for (String key : source.stringPropertyNames()) {
            resolved.setProperty(key, resolveValue(source.getProperty(key)));
        }
        return resolved;
    }

    private static String resolveValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Matcher matcher = PLACEHOLDER.matcher(raw);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String defaultValue = matcher.group(2);
            String replacement = firstNonBlank(
                System.getProperty(name),
                System.getenv(name),
                defaultValue,
                ""
            );
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
