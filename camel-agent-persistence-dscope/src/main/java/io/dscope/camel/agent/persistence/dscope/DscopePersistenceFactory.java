package io.dscope.camel.agent.persistence.dscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AuditGranularity;
import io.dscope.camel.persistence.core.FlowStateStoreFactory;
import io.dscope.camel.persistence.core.PersistenceConfiguration;
import java.util.Properties;

public final class DscopePersistenceFactory {

    static final String AUDIT_PERSISTENCE_PREFIX = "agent.audit.persistence.";

    private DscopePersistenceFactory() {
    }

    public static PersistenceFacade create(Properties properties, ObjectMapper objectMapper) {
        Properties effective = new Properties();
        effective.putAll(properties);
        effective.putIfAbsent("camel.persistence.enabled", "true");
        effective.putIfAbsent("camel.persistence.backend", "redis_jdbc");
        effective.putIfAbsent("agent.audit.granularity", "info");

        Properties auditEffective = buildAuditPersistenceProperties(effective);

        PersistenceConfiguration configuration = PersistenceConfiguration.fromProperties(effective);
        AuditGranularity auditGranularity = AuditGranularity.from(effective.getProperty("agent.audit.granularity"));
        var flowStateStore = FlowStateStoreFactory.create(configuration);
        if (auditEffective == null) {
            return new DscopePersistenceFacade(flowStateStore, objectMapper, auditGranularity);
        }
        var auditStore = FlowStateStoreFactory.create(PersistenceConfiguration.fromProperties(auditEffective));
        return new DscopePersistenceFacade(flowStateStore, auditStore, objectMapper, auditGranularity);
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
}
