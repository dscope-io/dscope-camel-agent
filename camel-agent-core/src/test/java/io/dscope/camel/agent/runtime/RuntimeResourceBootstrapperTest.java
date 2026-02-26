package io.dscope.camel.agent.runtime;

import java.util.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RuntimeResourceBootstrapperTest {

    @Test
    void shouldNormalizeGithubBlobToRawUrl() {
        String input = "https://github.com/acme/demo/blob/main/resources/agents/support/agent.md";

        String normalized = RuntimeResourceBootstrapper.normalizeUrl(input);

        Assertions.assertEquals(
            "https://raw.githubusercontent.com/acme/demo/main/resources/agents/support/agent.md",
            normalized
        );
    }

    @Test
    void shouldPreserveNonGithubUrl() {
        String input = "https://example.com/agents/support/agent.md";

        String normalized = RuntimeResourceBootstrapper.normalizeUrl(input);

        Assertions.assertEquals(input, normalized);
    }

    @Test
    void shouldMapKameletIncludePatternToCamelComponentLocation() {
        Properties source = new Properties();
        source.setProperty("agent.runtime.kamelets-include-pattern", "classpath:kamelets");

        Properties resolved = RuntimeResourceBootstrapper.resolve(source);

        Assertions.assertEquals("classpath:kamelets", resolved.getProperty("camel.component.kamelet.location"));
    }
}
