package io.dscope.camel.agent.runtime;

import java.util.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ApplicationYamlLoaderTest {

    @Test
    void shouldFlattenNestedYamlToDotProperties() throws Exception {
        Properties properties = ApplicationYamlLoader.loadFromClasspath("runtime/test-application.yaml");

        Assertions.assertEquals("classpath:agents/support/agent.md", properties.getProperty("agent.blueprint"));
        Assertions.assertEquals("classpath:agents/agents.yaml", properties.getProperty("agent.agents-config"));
        Assertions.assertEquals("debug", properties.getProperty("agent.audit.granularity"));
        Assertions.assertEquals("8080", properties.getProperty("agent.audit.api.port"));
        Assertions.assertEquals("jdbc:derby:memory:test;create=true", properties.getProperty("camel.persistence.jdbc.url"));
    }
}
