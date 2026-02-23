package io.dscope.camel.agent.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentAutoConfigurationTest {

    @Test
    void shouldRegisterProperties() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            .withPropertyValues(
                "agent.persistence-mode=redis_jdbc",
                "agent.chat-memory-enabled=false"
            );

        runner.run(context -> context.getBean(AgentStarterProperties.class));
    }
}
