package io.dscope.camel.agent;

import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MarkdownBlueprintLoaderTest {

    @Test
    void shouldParseBlueprintFromClasspath() {
        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("classpath:agents/valid-agent.md");

        Assertions.assertEquals("SupportAssistant", blueprint.name());
        Assertions.assertEquals("0.1.0", blueprint.version());
        Assertions.assertEquals(1, blueprint.tools().size());
        Assertions.assertEquals("kb.search", blueprint.tools().getFirst().name());
    }
}
