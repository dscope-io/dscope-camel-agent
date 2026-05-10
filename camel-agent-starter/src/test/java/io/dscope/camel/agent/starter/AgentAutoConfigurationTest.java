package io.dscope.camel.agent.starter;

import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.runtime.AsyncEventPersistenceFacade;
import org.junit.jupiter.api.Assertions;
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

    @Test
    void shouldEnableAsyncPersistenceByDefault() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            .withPropertyValues(
                "agent.persistence-mode=redis_jdbc",
                "agent.chat-memory-enabled=false"
            );

        runner.run(context -> Assertions.assertInstanceOf(
            AsyncEventPersistenceFacade.class,
            context.getBean(PersistenceFacade.class)
        ));
    }

    @Test
    void shouldAllowDisablingAsyncPersistence() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            .withPropertyValues(
                "agent.persistence-mode=redis_jdbc",
                "agent.chat-memory-enabled=false",
                "agent.audit-async-enabled=false"
            );

        runner.run(context -> Assertions.assertFalse(
            context.getBean(PersistenceFacade.class) instanceof AsyncEventPersistenceFacade
        ));
    }
}
