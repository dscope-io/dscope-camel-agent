package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.persistence.core.FlowStateStoreFactory;
import io.dscope.camel.persistence.core.PersistenceConfiguration;
import java.util.Properties;
import org.springframework.ai.chat.memory.ChatMemoryRepository;

public final class DscopeChatMemoryRepositoryFactory {

    private DscopeChatMemoryRepositoryFactory() {
    }

    public static ChatMemoryRepository create(Properties properties, ObjectMapper objectMapper) {
        Properties effective = new Properties();
        effective.putAll(properties);
        effective.putIfAbsent("camel.persistence.enabled", "true");
        effective.putIfAbsent("camel.persistence.backend", "redis_jdbc");
        PersistenceConfiguration configuration = PersistenceConfiguration.fromProperties(effective);
        return new DscopeChatMemoryRepository(FlowStateStoreFactory.create(configuration), objectMapper);
    }
}
