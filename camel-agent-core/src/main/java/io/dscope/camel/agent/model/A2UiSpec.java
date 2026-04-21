package io.dscope.camel.agent.model;

import java.util.List;

public record A2UiSpec(
    List<A2UiSurfaceSpec> surfaces
) {

    public A2UiSpec {
        surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
    }
}