package io.dscope.camel.agent.runtime;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RuntimePropertyPlaceholderResolver {

    private static final Pattern SPRING_PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private static final Pattern CAMEL_PLACEHOLDER = Pattern.compile("\\{\\{([^}:]+)(?::([^}]*))?}}");

    private RuntimePropertyPlaceholderResolver() {
    }

    static Properties resolve(Properties source) {
        Properties resolved = new Properties();
        if (source == null) {
            return resolved;
        }
        for (String key : source.stringPropertyNames()) {
            resolved.setProperty(key, resolveProperty(source, resolved, key, new HashSet<>()));
        }
        return resolved;
    }

    private static String resolveProperty(Properties source,
                                          Properties resolved,
                                          String key,
                                          Set<String> visiting) {
        String cached = resolved.getProperty(key);
        if (cached != null) {
            return cached;
        }

        String systemValue = firstNonBlank(System.getProperty(key), System.getenv(key));
        if (systemValue != null) {
            return systemValue;
        }

        String raw = source.getProperty(key);
        if (raw == null) {
            return "";
        }

        if (!visiting.add(key)) {
            return raw;
        }
        String value = resolveValue(source, resolved, raw, visiting);
        visiting.remove(key);
        resolved.setProperty(key, value);
        return value;
    }

    private static String resolveValue(Properties source,
                                       Properties resolved,
                                       String raw,
                                       Set<String> visiting) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String current = raw;
        for (int attempt = 0; attempt < 10; attempt++) {
            String next = substitute(current, source, resolved, visiting, SPRING_PLACEHOLDER);
            next = substitute(next, source, resolved, visiting, CAMEL_PLACEHOLDER);
            if (next.equals(current)) {
                return next;
            }
            current = next;
        }
        return current;
    }

    private static String substitute(String raw,
                                     Properties source,
                                     Properties resolved,
                                     Set<String> visiting,
                                     Pattern pattern) {
        Matcher matcher = pattern.matcher(raw);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String defaultValue = matcher.group(2);
            String replacement = firstNonBlank(
                System.getProperty(name),
                System.getenv(name),
                resolveFromProperties(source, resolved, name, visiting),
                defaultValue
            );
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String resolveFromProperties(Properties source,
                                                Properties resolved,
                                                String name,
                                                Set<String> visiting) {
        if (name.isBlank()) {
            return null;
        }
        String cached = resolved.getProperty(name);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        if (source.getProperty(name) == null) {
            return null;
        }
        return resolveProperty(source, resolved, name, visiting);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
