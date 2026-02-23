package io.dscope.camel.agent.registry;

import io.dscope.camel.agent.api.ToolRegistry;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, ToolSpec> tools;

    public DefaultToolRegistry(List<ToolSpec> tools) {
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(ToolSpec::name, Function.identity()));
    }

    @Override
    public List<ToolSpec> listTools() {
        return tools.values().stream().toList();
    }

    @Override
    public Optional<ToolSpec> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }
}
