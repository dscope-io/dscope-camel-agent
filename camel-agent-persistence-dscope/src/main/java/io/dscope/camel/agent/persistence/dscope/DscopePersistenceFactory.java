package io.dscope.camel.agent.persistence.dscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AuditGranularity;
import io.dscope.camel.persistence.core.FlowStateStoreFactory;
import io.dscope.camel.persistence.core.PersistenceConfiguration;
import java.util.Properties;

public final class DscopePersistenceFactory {

    private DscopePersistenceFactory() {
    }

    public static PersistenceFacade create(Properties properties, ObjectMapper objectMapper) {
        Properties effective = new Properties();
        effective.putAll(properties);
        effective.putIfAbsent("camel.persistence.enabled", "true");
        effective.putIfAbsent("camel.persistence.backend", "redis_jdbc");
        effective.putIfAbsent("agent.audit.granularity", "info");
        PersistenceConfiguration configuration = PersistenceConfiguration.fromProperties(effective);
        AuditGranularity auditGranularity = AuditGranularity.from(effective.getProperty("agent.audit.granularity"));
        return new DscopePersistenceFacade(FlowStateStoreFactory.create(configuration), objectMapper, auditGranularity);
    }
}
