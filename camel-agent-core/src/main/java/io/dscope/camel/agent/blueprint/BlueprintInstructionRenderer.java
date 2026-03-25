package io.dscope.camel.agent.blueprint;

import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.ResolvedBlueprintResource;
import java.util.ArrayList;
import java.util.List;

public final class BlueprintInstructionRenderer {

    private BlueprintInstructionRenderer() {
    }

    public static String renderForChat(AgentBlueprint blueprint) {
        return render(blueprint, "chat", Integer.MAX_VALUE);
    }

    public static String renderForChat(AgentBlueprint blueprint, int maxChars) {
        return render(blueprint, "chat", maxChars <= 0 ? Integer.MAX_VALUE : maxChars);
    }

    public static String renderForRealtime(AgentBlueprint blueprint, int maxChars) {
        return render(blueprint, "realtime", maxChars <= 0 ? Integer.MAX_VALUE : maxChars);
    }

    private static String render(AgentBlueprint blueprint, String target, int maxChars) {
        if (blueprint == null) {
            return "You are a helpful agent.";
        }
        String base = blueprint.systemInstruction() == null || blueprint.systemInstruction().isBlank()
            ? "You are a helpful agent."
            : blueprint.systemInstruction().trim();
        if (blueprint.resources() == null || blueprint.resources().isEmpty()) {
            return base;
        }

        List<String> blocks = new ArrayList<>();
        for (ResolvedBlueprintResource resource : blueprint.resources()) {
            if (resource == null || !resource.includedIn(target)) {
                continue;
            }
            String text = resource.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            blocks.add("Resource: " + resource.spec().name() + "\nSource: " + resource.resolvedUri() + "\n" + text.trim());
        }
        if (blocks.isEmpty()) {
            return base;
        }

        String combined = base + "\n\nReference resources:\n\n" + String.join("\n\n---\n\n", blocks);
        if (combined.length() <= maxChars) {
            return combined;
        }
        return combined.substring(0, Math.max(0, maxChars)).trim();
    }
}