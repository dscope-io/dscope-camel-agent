package io.dscope.camel.agent.starter;

import com.zaxxer.hikari.HikariDataSource;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.runtime.AsyncEventPersistenceFacade;
import io.dscope.camel.persistence.core.FlowStateStore;
import io.dscope.camel.persistence.core.PersistenceConfiguration;
import io.dscope.camel.persistence.jdbc.JdbcFlowStateStore;
import io.dscope.camel.persistence.jdbc.JdbcFlowStateStoreProvider;
import java.lang.reflect.Field;
import java.util.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class AgentAutoConfigurationTest {

    @Test
    void shouldResolveConfiguredToolResultLimitAndDefault() {
        Assertions.assertEquals(10_000, AgentAutoConfiguration.toolResultMaxChars(new MockEnvironment()));
        Assertions.assertEquals(500_000, AgentAutoConfiguration.toolResultMaxChars(new MockEnvironment()
            .withProperty("agent.runtime.chat.tool-result-max-chars", "500000")));
        Assertions.assertEquals(10_000, AgentAutoConfiguration.toolResultMaxChars(new MockEnvironment()
            .withProperty("agent.runtime.chat.tool-result-max-chars", "0")));
    }

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

    @Test
    void shouldPassCanonicalJdbcUserAndPasswordToHikari() throws Exception {
        Properties properties = AgentAutoConfiguration.persistenceProperties(new AgentStarterProperties(), new MockEnvironment()
            .withProperty("camel.persistence.backend", "jdbc")
            .withProperty("camel.persistence.jdbc.url", "jdbc:derby:memory:starter-credentials;create=true")
            .withProperty("camel.persistence.jdbc.user", "agent_user")
            .withProperty("camel.persistence.jdbc.password", "agent_password"));

        PersistenceConfiguration configuration = PersistenceConfiguration.fromProperties(properties);
        Assertions.assertEquals("agent_user", configuration.jdbcUser());
        Assertions.assertEquals("agent_password", configuration.jdbcPassword());

        FlowStateStore store = new JdbcFlowStateStoreProvider().create(configuration);
        try {
            HikariDataSource dataSource = hikariDataSource(store);
            Assertions.assertEquals("agent_user", dataSource.getUsername());
            Assertions.assertEquals("agent_password", dataSource.getPassword());
        } finally {
            store.close();
        }
    }

    @Test
    void shouldMapLegacyJdbcUsernameToCanonicalUser() {
        Properties properties = AgentAutoConfiguration.persistenceProperties(new AgentStarterProperties(), new MockEnvironment()
            .withProperty("camel.persistence.jdbc.username", "legacy_user")
            .withProperty("camel.persistence.jdbc.password", "agent_password"));

        Assertions.assertEquals("legacy_user", properties.getProperty("camel.persistence.jdbc.user"));
        Assertions.assertNull(properties.getProperty("camel.persistence.jdbc.username"));
        Assertions.assertEquals("agent_password", PersistenceConfiguration.fromProperties(properties).jdbcPassword());
    }

    @Test
    void shouldPreferCanonicalJdbcUserOverLegacyUsername() {
        Properties properties = AgentAutoConfiguration.persistenceProperties(new AgentStarterProperties(), new MockEnvironment()
            .withProperty("camel.persistence.jdbc.user", "canonical_user")
            .withProperty("camel.persistence.jdbc.username", "legacy_user"));

        Assertions.assertEquals("canonical_user", properties.getProperty("camel.persistence.jdbc.user"));
    }

    private HikariDataSource hikariDataSource(FlowStateStore store) throws Exception {
        Field dataSource = JdbcFlowStateStore.class.getDeclaredField("dataSource");
        dataSource.setAccessible(true);
        return Assertions.assertInstanceOf(HikariDataSource.class, dataSource.get(store));
    }
}
