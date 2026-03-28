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

    @Test
    void shouldResolveCamelStylePlaceholdersAgainstLoadedProperties() {
        Properties source = new Properties();
        source.setProperty("agent.runtime.test-port", "19091");
        source.setProperty("agui.rpc.port", "{{agent.runtime.test-port:18081}}");
        source.setProperty("agent.runtime.a2a.port", "{{agui.rpc.port:8080}}");
        source.setProperty("agent.runtime.a2a.public-base-url", "http://127.0.0.1:{{agui.rpc.port:8080}}");

        Properties resolved = RuntimePropertyPlaceholderResolver.resolve(source);

        Assertions.assertEquals("19091", resolved.getProperty("agui.rpc.port"));
        Assertions.assertEquals("19091", resolved.getProperty("agent.runtime.a2a.port"));
        Assertions.assertEquals(
            "http://127.0.0.1:19091",
            resolved.getProperty("agent.runtime.a2a.public-base-url")
        );
    }
}
