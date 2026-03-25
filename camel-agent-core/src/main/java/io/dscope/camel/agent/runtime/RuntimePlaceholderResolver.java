package io.dscope.camel.agent.runtime;

import io.dscope.camel.agent.model.AgUiPreRunSpec;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.RealtimeSpec;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.camel.CamelContext;

public final class RuntimePlaceholderResolver {

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)(?::([^}]*))?}");
    private static final Pattern CAMEL_PATTERN = Pattern.compile("\\{\\{[^}]+}}");

    private RuntimePlaceholderResolver() {
    }

    public static AgentBlueprint resolveExecutionValues(CamelContext camelContext, AgentBlueprint blueprint) {
        if (blueprint == null) {
            return null;
        }
        String blueprintName = blueprint.name();
        List<ToolSpec> resolvedTools = new ArrayList<>();
        if (blueprint.tools() != null) {
            for (ToolSpec tool : blueprint.tools()) {
                resolvedTools.add(resolveToolSpec(camelContext, tool, blueprintName));
            }
        }
        return new AgentBlueprint(
            blueprint.name(),
            blueprint.version(),
            blueprint.systemInstruction(),
            resolvedTools,
            blueprint.jsonRouteTemplates(),
            blueprint.mcpToolCatalogs(),
            resolveRealtimeSpec(camelContext, blueprint.realtime(), blueprintName),
            resolveAgUiPreRunSpec(camelContext, blueprint.aguiPreRun(), blueprintName),
            blueprint.resources()
        );
    }

    public static ToolSpec resolveToolSpec(CamelContext camelContext, ToolSpec toolSpec) {
        return resolveToolSpec(camelContext, toolSpec, null);
    }

    public static ToolSpec resolveToolSpec(CamelContext camelContext, ToolSpec toolSpec, String blueprintName) {
        if (toolSpec == null) {
            return null;
        }
        String toolContext = toolContext(toolSpec.name(), blueprintName);
        return new ToolSpec(
            toolSpec.name(),
            toolSpec.description(),
            resolveRequiredExecutionTarget(camelContext, toolSpec.routeId(), contextualFieldName(toolContext, "tools[].routeId")),
            resolveRequiredExecutionTarget(camelContext, toolSpec.endpointUri(), contextualFieldName(toolContext, "tools[].endpointUri")),
            toolSpec.inputSchema(),
            toolSpec.outputSchema(),
            toolSpec.policy()
        );
    }

    public static AgUiPreRunSpec resolveAgUiPreRunSpec(CamelContext camelContext, AgUiPreRunSpec spec) {
        return resolveAgUiPreRunSpec(camelContext, spec, null);
    }

    public static AgUiPreRunSpec resolveAgUiPreRunSpec(CamelContext camelContext, AgUiPreRunSpec spec, String blueprintName) {
        if (spec == null) {
            return null;
        }
        String context = blueprintContext(blueprintName);
        return new AgUiPreRunSpec(
            resolveRequiredExecutionTarget(camelContext, spec.agentEndpointUri(), contextualFieldName(context, "aguiPreRun.agentEndpointUri")),
            spec.fallbackEnabled(),
            spec.kbToolName(),
            spec.ticketToolName(),
            resolveRequiredExecutionTarget(camelContext, spec.kbUri(), contextualFieldName(context, "aguiPreRun.fallback.kbUri")),
            resolveRequiredExecutionTarget(camelContext, spec.ticketUri(), contextualFieldName(context, "aguiPreRun.fallback.ticketUri")),
            spec.ticketKeywords(),
            spec.fallbackErrorMarkers()
        );
    }

    public static RealtimeSpec resolveRealtimeSpec(CamelContext camelContext, RealtimeSpec spec) {
        return resolveRealtimeSpec(camelContext, spec, null);
    }

    public static RealtimeSpec resolveRealtimeSpec(CamelContext camelContext, RealtimeSpec spec, String blueprintName) {
        if (spec == null) {
            return null;
        }
        String context = blueprintContext(blueprintName);
        return new RealtimeSpec(
            resolveString(camelContext, spec.provider()),
            resolveString(camelContext, spec.model()),
            resolveString(camelContext, spec.voice()),
            resolveString(camelContext, spec.transport()),
            resolveRequiredExecutionTarget(camelContext, spec.endpointUri(), contextualFieldName(context, "realtime.endpointUri")),
            resolveString(camelContext, spec.inputAudioFormat()),
            resolveString(camelContext, spec.outputAudioFormat()),
            resolveString(camelContext, spec.retentionPolicy()),
            spec.reconnectMaxSendRetries(),
            spec.reconnectMaxReconnects(),
            spec.reconnectInitialBackoffMs(),
            spec.reconnectMaxBackoffMs()
        );
    }

    public static String resolveString(CamelContext camelContext, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String resolved = resolveCamelPlaceholders(camelContext, value);
        return resolveEnvironmentPlaceholders(resolved);
    }

    public static String resolveRequiredExecutionTarget(CamelContext camelContext, String value, String fieldName) {
        String resolved = resolveString(camelContext, value);
        if (resolved == null || resolved.isBlank()) {
            return resolved;
        }
        if (containsUnresolvedPlaceholder(resolved)) {
            throw new IllegalArgumentException(
                "Unresolved runtime placeholder in " + safeFieldName(fieldName) + ": " + resolved
            );
        }
        return resolved;
    }

    private static String resolveCamelPlaceholders(CamelContext camelContext, String value) {
        if (camelContext == null || value == null || !value.contains("{{")) {
            return value;
        }
        try {
            return camelContext.resolvePropertyPlaceholders(value);
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String resolveEnvironmentPlaceholders(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        Matcher matcher = ENV_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String defaultValue = matcher.group(2);
            String replacement = firstNonBlank(
                System.getenv(name),
                System.getProperty(name),
                defaultValue
            );
            if (replacement == null) {
                replacement = matcher.group(0);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static boolean containsUnresolvedPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.contains("${") || CAMEL_PATTERN.matcher(value).find();
    }

    private static String safeFieldName(String fieldName) {
        return fieldName == null || fieldName.isBlank() ? "execution target" : fieldName;
    }

    private static String contextualFieldName(String context, String fieldName) {
        if (context == null || context.isBlank()) {
            return fieldName;
        }
        return context + " " + safeFieldName(fieldName);
    }

    private static String toolContext(String toolName, String blueprintName) {
        String tool = toolName == null || toolName.isBlank() ? "tool" : "tool '" + toolName + "'";
        String blueprint = blueprintContext(blueprintName);
        return blueprint == null || blueprint.isBlank() ? tool : tool + " in " + blueprint;
    }

    private static String blueprintContext(String blueprintName) {
        if (blueprintName == null || blueprintName.isBlank()) {
            return null;
        }
        return "blueprint '" + blueprintName + "'";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}