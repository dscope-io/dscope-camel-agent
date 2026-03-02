package io.dscope.camel.agent.persistence.dscope;

import java.util.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DscopePersistenceFactoryTest {

    @Test
    void shouldResolvePostgresDefaultDdlResource() {
        String resource = DscopePersistenceFactory.resolveSchemaDdlResource(new Properties(), "jdbc:postgresql://localhost:5432/agent");

        Assertions.assertEquals(DscopePersistenceFactory.DEFAULT_POSTGRES_DDL_RESOURCE, resource);
    }

    @Test
    void shouldResolveSnowflakeDefaultDdlResource() {
        String resource = DscopePersistenceFactory.resolveSchemaDdlResource(new Properties(), "jdbc:snowflake://acme.snowflakecomputing.com");

        Assertions.assertEquals(DscopePersistenceFactory.DEFAULT_SNOWFLAKE_DDL_RESOURCE, resource);
    }

    @Test
    void shouldUseOverrideDdlResourceWhenConfigured() {
        Properties properties = new Properties();
        properties.setProperty(DscopePersistenceFactory.SCHEMA_DDL_RESOURCE_PROPERTY, "classpath:db/custom-ddl.sql");

        String resource = DscopePersistenceFactory.resolveSchemaDdlResource(properties, "jdbc:postgresql://localhost:5432/agent");

        Assertions.assertEquals("classpath:db/custom-ddl.sql", resource);
    }

    @Test
    void shouldReturnNullDdlResourceForUnknownJdbcVendorWithoutOverride() {
        String resource = DscopePersistenceFactory.resolveSchemaDdlResource(new Properties(), "jdbc:oracle:thin:@localhost:1521/ORCLCDB");

        Assertions.assertNull(resource);
    }

    @Test
    void shouldReturnNullWhenNoAuditPersistenceOverrideConfigured() {
        Properties properties = new Properties();
        properties.setProperty("camel.persistence.backend", "redis_jdbc");
        properties.setProperty("agent.audit.granularity", "debug");

        Properties audit = DscopePersistenceFactory.buildAuditPersistenceProperties(properties);

        Assertions.assertNull(audit);
    }

    @Test
    void shouldBuildAuditJdbcPropertiesWhenAuditJdbcUrlProvided() {
        Properties properties = new Properties();
        properties.setProperty("camel.persistence.backend", "redis_jdbc");
        properties.setProperty("agent.audit.jdbc.url", "jdbc:postgresql://audit-db/audit");

        Properties audit = DscopePersistenceFactory.buildAuditPersistenceProperties(properties);

        Assertions.assertNotNull(audit);
        Assertions.assertEquals("true", audit.getProperty("camel.persistence.enabled"));
        Assertions.assertEquals("jdbc", audit.getProperty("camel.persistence.backend"));
        Assertions.assertEquals("jdbc:postgresql://audit-db/audit", audit.getProperty("camel.persistence.jdbc.url"));
    }

    @Test
    void shouldMapNamespacedAuditPersistenceProperties() {
        Properties properties = new Properties();
        properties.setProperty("agent.audit.persistence.backend", "jdbc");
        properties.setProperty("agent.audit.persistence.jdbc.url", "jdbc:h2:mem:audit");
        properties.setProperty("agent.audit.persistence.jdbc.username", "audit_user");

        Properties audit = DscopePersistenceFactory.buildAuditPersistenceProperties(properties);

        Assertions.assertNotNull(audit);
        Assertions.assertEquals("jdbc", audit.getProperty("camel.persistence.backend"));
        Assertions.assertEquals("jdbc:h2:mem:audit", audit.getProperty("camel.persistence.jdbc.url"));
        Assertions.assertEquals("audit_user", audit.getProperty("camel.persistence.jdbc.username"));
    }
}
