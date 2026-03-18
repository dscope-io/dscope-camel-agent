package io.dscope.camel.agent.persistence.dscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AuditGranularity;
import io.dscope.camel.persistence.core.FlowStateStore;
import io.dscope.camel.persistence.core.FlowStateStoreFactory;
import io.dscope.camel.persistence.core.PersistenceBackend;
import io.dscope.camel.persistence.core.PersistenceConfiguration;
import java.util.Properties;

public final class DscopePersistenceFactory {

    static final String AUDIT_PERSISTENCE_PREFIX = "agent.audit.persistence.";
    static final String SCHEMA_DDL_RESOURCE_PROPERTY = "camel.persistence.jdbc.schema.ddl-resource";
    static final String DEFAULT_POSTGRES_DDL_RESOURCE = "classpath:db/persistence/postgres-flow-state.sql";
    static final String DEFAULT_SNOWFLAKE_DDL_RESOURCE = "classpath:db/persistence/snowflake-flow-state.sql";

    private DscopePersistenceFactory() {
    }

    public static PersistenceFacade create(Properties properties, ObjectMapper objectMapper) {
        Properties effective = new Properties();
        effective.putAll(properties);
        effective.putIfAbsent("camel.persistence.enabled", "true");
        effective.put("camel.persistence.backend", normalizeBackend(effective.getProperty("camel.persistence.backend", "redis_jdbc")));
        effective.putIfAbsent("agent.audit.granularity", "info");

        Properties auditEffective = buildAuditPersistenceProperties(effective);

        PersistenceConfiguration configuration = PersistenceConfiguration.fromProperties(effective);
        AuditGranularity auditGranularity = AuditGranularity.from(effective.getProperty("agent.audit.granularity"));
        var flowStateStore = createFlowStateStore(configuration, effective);
        if (auditEffective == null) {
            return new DscopePersistenceFacade(flowStateStore, objectMapper, auditGranularity);
        }
        var auditStore = createFlowStateStore(PersistenceConfiguration.fromProperties(auditEffective), auditEffective);
        return new DscopePersistenceFacade(flowStateStore, auditStore, objectMapper, auditGranularity);
    }

    static FlowStateStore createFlowStateStore(PersistenceConfiguration configuration, Properties effective) {
        if (configuration == null) {
            throw new IllegalArgumentException("Persistence configuration cannot be null");
        }

        PersistenceBackend backend = configuration.backend();
        if (isJdbcBacked(backend)) {
            String ddlResource = resolveSchemaDdlResource(effective, configuration.jdbcUrl());
            if (ddlResource != null && !ddlResource.isBlank()) {
                return new ScriptedJdbcFlowStateStore(
                    configuration.jdbcUrl(),
                    configuration.jdbcUser(),
                    configuration.jdbcPassword(),
                    ddlResource
                );
            }
        }

        return FlowStateStoreFactory.create(configuration);
    }

    private static boolean isJdbcBacked(PersistenceBackend backend) {
        if (backend == null) {
            return false;
        }
        return backend == PersistenceBackend.JDBC || "REDIS_JDBC".equals(backend.name());
    }

    private static String normalizeBackend(String backend) {
        if (backend == null || backend.isBlank()) {
            return "jdbc";
        }
        if ("redis_jdbc".equalsIgnoreCase(backend) || "redis-jdbc".equalsIgnoreCase(backend)) {
            return "jdbc";
        }
        return backend;
    }

    static String resolveSchemaDdlResource(Properties effective, String jdbcUrl) {
        if (effective != null) {
            String override = effective.getProperty(SCHEMA_DDL_RESOURCE_PROPERTY);
            if (override != null && !override.isBlank()) {
                return override;
            }
        }
        if (isPostgresJdbcUrl(jdbcUrl)) {
            return DEFAULT_POSTGRES_DDL_RESOURCE;
        }
        if (isSnowflakeJdbcUrl(jdbcUrl)) {
            return DEFAULT_SNOWFLAKE_DDL_RESOURCE;
        }
        return null;
    }

    static Properties buildAuditPersistenceProperties(Properties effective) {
        Properties audit = new Properties();
        boolean overridden = false;

        for (String key : effective.stringPropertyNames()) {
            if (key.startsWith(AUDIT_PERSISTENCE_PREFIX)) {
                String suffix = key.substring(AUDIT_PERSISTENCE_PREFIX.length());
                if (!suffix.isBlank()) {
                    audit.setProperty("camel.persistence." + suffix, effective.getProperty(key));
                    overridden = true;
                }
            }
        }

        String auditJdbcUrl = effective.getProperty("agent.audit.jdbc.url");
        if (auditJdbcUrl != null && !auditJdbcUrl.isBlank()) {
            audit.setProperty("camel.persistence.jdbc.url", auditJdbcUrl);
            overridden = true;
        }

        copyIfPresent(effective, audit, "agent.audit.jdbc.username", "camel.persistence.jdbc.username");
        copyIfPresent(effective, audit, "agent.audit.jdbc.password", "camel.persistence.jdbc.password");
        copyIfPresent(effective, audit, "agent.audit.jdbc.driver-class-name", "camel.persistence.jdbc.driver-class-name");
        copyIfPresent(effective, audit, "agent.audit.jdbc.driverClassName", "camel.persistence.jdbc.driverClassName");
        copyIfPresent(effective, audit, "agent.audit.backend", "camel.persistence.backend");

        if (!overridden) {
            return null;
        }

        audit.putIfAbsent("camel.persistence.enabled", "true");
        audit.putIfAbsent("camel.persistence.backend", "jdbc");
        return audit;
    }

    private static void copyIfPresent(Properties source, Properties target, String sourceKey, String targetKey) {
        String value = source.getProperty(sourceKey);
        if (value != null && !value.isBlank()) {
            target.setProperty(targetKey, value);
        }
    }

    private static boolean isPostgresJdbcUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.trim().toLowerCase().startsWith("jdbc:postgresql:");
    }

    private static boolean isSnowflakeJdbcUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.trim().toLowerCase().startsWith("jdbc:snowflake:");
    }
}
