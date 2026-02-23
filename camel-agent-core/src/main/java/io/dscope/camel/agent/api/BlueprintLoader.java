package io.dscope.camel.agent.api;

import io.dscope.camel.agent.model.AgentBlueprint;

public interface BlueprintLoader {

    AgentBlueprint load(String location);
}
