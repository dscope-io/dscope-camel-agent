package io.dscope.camel.agent.runtime;

import java.util.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RuntimePropertyPlaceholderResolverTest {

    @Test
    void shouldResolveDefaultWithCommaSeparatedRoutes() {
        Properties source = new Properties();
        source.setProperty("agent.runtime.routes-include-pattern",
            "${AGENT_ROUTES_INCLUDE_PATTERN:classpath:routes/a.yaml,classpath:routes/b.xml}");

        Properties resolved = RuntimePropertyPlaceholderResolver.resolve(source);

        Assertions.assertEquals(
            "classpath:routes/a.yaml,classpath:routes/b.xml",
            resolved.getProperty("agent.runtime.routes-include-pattern")
        );
    }
}
