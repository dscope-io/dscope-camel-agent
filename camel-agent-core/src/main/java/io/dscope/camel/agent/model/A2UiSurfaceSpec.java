package io.dscope.camel.agent.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record A2UiSurfaceSpec(
    String name,
    String widgetTemplate,
    String surfaceIdTemplate,
    String catalogId,
    String catalogResource,
    String surfaceResource,
    List<String> matchFields,
    Map<String, String> localeResources
) {

    public A2UiSurfaceSpec {
        matchFields = matchFields == null ? List.of() : List.copyOf(matchFields);
        localeResources = localeResources == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(localeResources));
    }
}