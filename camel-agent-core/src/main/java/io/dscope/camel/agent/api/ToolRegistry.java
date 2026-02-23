package io.dscope.camel.agent.api;

import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.Optional;

public interface ToolRegistry {

    List<ToolSpec> listTools();

    Optional<ToolSpec> getTool(String name);
}
