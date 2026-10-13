package io.dscope.camel.agent.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.AgentKernel;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.api.BlueprintLoader;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.api.ToolRegistry;
import io.dscope.camel.agent.a2a.A2AToolContext;
import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.executor.CamelToolExecutor;
import io.dscope.camel.agent.kernel.DefaultAgentKernel;
import io.dscope.camel.agent.mcp.McpToolDiscoveryResolver;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.persistence.dscope.DscopePersistenceFactory;
import io.dscope.camel.agent.registry.DefaultToolRegistry;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.agent.runtime.AsyncEventPersistenceFacade;
import io.dscope.camel.agent.runtime.ResolvedAgentPlan;
import io.dscope.camel.agent.springai.DscopeChatMemoryRepositoryFactory;
import io.dscope.camel.agent.springai.NoopSpringAiChatGateway;
import io.dscope.camel.agent.springai.SpringAiModelClient;
import io.dscope.camel.agent.validation.SchemaValidator;
import java.util.Properties;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@EnableConfigurationProperties(AgentStarterProperties.class)
public class AgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ChatMemoryRepository.class)
    @ConditionalOnProperty(prefix = "agent", name = "chat-memory-enabled", havingValue = "true", matchIfMissing = true)
    public ChatMemoryRepository chatMemoryRepository(AgentStarterProperties properties, ObjectMapper objectMapper, Environment environment) {
        Properties config = persistenceProperties(properties, environment);
        config.setProperty("camel.persistence.enabled", "true");
        return DscopeChatMemoryRepositoryFactory.create(config, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ChatMemory.class)
    @ConditionalOnBean(ChatMemoryRepository.class)
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, AgentStarterProperties properties) {
        return MessageWindowChatMemory.builder()
            .chatMemoryRepository(chatMemoryRepository)
            .maxMessages(properties.getChatMemoryWindow())
            .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public BlueprintLoader blueprintLoader() {
        return new MarkdownBlueprintLoader();
    }

    @Bean
    @ConditionalOnMissingBean
    public AiModelClient aiModelClient(ObjectMapper objectMapper, Environment environment) {
        return new SpringAiModelClient(
            new NoopSpringAiChatGateway(),
            objectMapper,
            toolResultMaxChars(environment)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public PersistenceFacade persistenceFacade(AgentStarterProperties properties, ObjectMapper objectMapper, Environment environment) {
        Properties config = persistenceProperties(properties, environment);
        copyIfPresent(config, "agent.audit.backend", properties.getAuditPersistenceBackend());
        copyIfPresent(config, "agent.audit.jdbc.url", properties.getAuditJdbcUrl());
        copyIfPresent(config, "agent.audit.jdbc.username", properties.getAuditJdbcUsername());
        copyIfPresent(config, "agent.audit.jdbc.password", properties.getAuditJdbcPassword());
        copyIfPresent(config, "agent.audit.jdbc.driver-class-name", properties.getAuditJdbcDriverClassName());
        PersistenceFacade persistenceFacade = DscopePersistenceFactory.create(config, objectMapper);
        if (!properties.isAuditAsyncEnabled()) {
            return persistenceFacade;
        }
        return new AsyncEventPersistenceFacade(
            persistenceFacade,
            "starter-main",
            properties.getAuditAsyncQueueCapacity(),
            properties.getAuditAsyncRetryDelayMs(),
            properties.getAuditAsyncShutdownTimeoutMs(),
            properties.getAuditAsyncMetricsLogIntervalMs(),
            properties.getAuditAsyncMaxRetryAttempts()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentPlanSelectionResolver agentPlanSelectionResolver(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
        return new AgentPlanSelectionResolver(persistenceFacade, objectMapper);
    }

    private static void copyIfPresent(Properties config, String key, String value) {
        if (value != null && !value.isBlank()) {
            config.setProperty(key, value);
        }
    }

    static Properties persistenceProperties(AgentStarterProperties properties, Environment environment) {
        Properties config = new Properties();
        String[] keys = {
            "camel.persistence.enabled", "camel.persistence.backend", "camel.persistence.snapshot-every-events",
            "camel.persistence.max-replay-events", "camel.persistence.read-batch-size", "camel.persistence.redis.uri",
            "camel.persistence.redis.key-prefix", "camel.persistence.jdbc.url",
            "camel.persistence.jdbc.password", "camel.persistence.jdbc.driver-class-name",
            "camel.persistence.jdbc.schema-ddl-resource", "camel.persistence.jdbc.pool.enabled",
            "camel.persistence.jdbc.pool.max-size", "camel.persistence.jdbc.pool.min-idle",
            "camel.persistence.jdbc.pool.connection-timeout-ms", "camel.persistence.jdbc.pool.idle-timeout-ms",
            "camel.persistence.jdbc.pool.max-lifetime-ms"
        };
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                config.setProperty(key, value);
            }
        }
        copyIfPresent(config, "camel.persistence.jdbc.user", firstNonBlank(
            environment.getProperty("camel.persistence.jdbc.user"),
            environment.getProperty("camel.persistence.jdbc.username")
        ));
        config.setProperty("camel.persistence.enabled", config.getProperty("camel.persistence.enabled", "true"));
        config.setProperty("camel.persistence.backend", config.getProperty("camel.persistence.backend",
            normalizePersistenceBackend(properties.getPersistenceMode())));
        config.setProperty("agent.audit.granularity", properties.getAuditGranularity());
        return config;
    }

    static int toolResultMaxChars(Environment environment) {
        String[] keys = {
            "agent.runtime.chat.tool-result-max-chars",
            "agent.runtime.chat.toolResultMaxChars",
            "agent.chat.tool-result-max-chars",
            "agent.chat.toolResultMaxChars"
        };
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                int parsed = Integer.parseInt(value.trim());
                return parsed > 0 ? parsed : SpringAiModelClient.DEFAULT_TOOL_RESULT_MAX_CHARS;
            } catch (NumberFormatException ignored) {
                return SpringAiModelClient.DEFAULT_TOOL_RESULT_MAX_CHARS;
            }
        }
        return SpringAiModelClient.DEFAULT_TOOL_RESULT_MAX_CHARS;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String normalizePersistenceBackend(String backend) {
        if (backend == null || backend.isBlank()) {
            return "jdbc";
        }
        if ("redis_jdbc".equalsIgnoreCase(backend) || "redis-jdbc".equalsIgnoreCase(backend)) {
            return "jdbc";
        }
        return backend;
    }

    @Bean
    @ConditionalOnBean(CamelContext.class)
    @ConditionalOnMissingBean
    public AgentKernel agentKernel(CamelContext camelContext,
                                   BlueprintLoader blueprintLoader,
                                   AiModelClient aiModelClient,
                                   PersistenceFacade persistenceFacade,
                                   AgentPlanSelectionResolver planSelectionResolver,
                                   AgentStarterProperties properties,
                                   ObjectMapper objectMapper) {
        String blueprintLocation = properties.getBlueprint();
        ResolvedAgentPlan resolvedPlan = null;
        if (properties.getAgentsConfig() != null && !properties.getAgentsConfig().isBlank()) {
            resolvedPlan = planSelectionResolver.resolve(
                null,
                null,
                null,
                properties.getAgentsConfig(),
                properties.getBlueprint()
            );
            blueprintLocation = resolvedPlan.blueprint();
        }
        AgentBlueprint loadedBlueprint = blueprintLoader.load(blueprintLocation);
        ProducerTemplate producerTemplate = camelContext.createProducerTemplate();
        AgentBlueprint blueprint = McpToolDiscoveryResolver.resolve(loadedBlueprint, producerTemplate, objectMapper);
        ToolRegistry toolRegistry = new DefaultToolRegistry(blueprint.tools());
        ToolExecutor toolExecutor = createToolExecutor(camelContext, producerTemplate, objectMapper, blueprint, persistenceFacade, resolvedPlan);
        return new DefaultAgentKernel(
            blueprint,
            toolRegistry,
            toolExecutor,
            aiModelClient,
            persistenceFacade,
            new SchemaValidator(),
            objectMapper,
            properties.getTaskClaimOwnerId(),
            properties.getTaskClaimLeaseSeconds(),
            properties.isHistoryRehydrateFromPersistence()
        );
    }

    private ToolExecutor createToolExecutor(CamelContext camelContext,
                                            ProducerTemplate producerTemplate,
                                            ObjectMapper objectMapper,
                                            AgentBlueprint blueprint,
                                            PersistenceFacade persistenceFacade,
                                            ResolvedAgentPlan resolvedPlan) {
        try {
            java.util.List<?> templates = readJsonRouteTemplates(blueprint);
            Class<?> type = Class.forName("io.dscope.camel.agent.executor.TemplateAwareCamelToolExecutor");
            return (ToolExecutor) type
                .getConstructor(CamelContext.class, ProducerTemplate.class, ObjectMapper.class, java.util.List.class,
                    PersistenceFacade.class, A2AToolContext.class)
                .newInstance(
                    camelContext,
                    producerTemplate,
                    objectMapper,
                    templates,
                    persistenceFacade,
                    new A2AToolContext(
                        resolvedPlan == null || resolvedPlan.legacyMode() ? "" : resolvedPlan.planName(),
                        resolvedPlan == null || resolvedPlan.legacyMode() ? "" : resolvedPlan.planVersion(),
                        blueprint.name() == null ? "" : blueprint.name(),
                        blueprint.version() == null ? "" : blueprint.version()
                    )
                );
        } catch (Exception ignored) {
            return new CamelToolExecutor(
                camelContext,
                producerTemplate,
                objectMapper,
                persistenceFacade,
                new A2AToolContext(
                    resolvedPlan == null || resolvedPlan.legacyMode() ? "" : resolvedPlan.planName(),
                    resolvedPlan == null || resolvedPlan.legacyMode() ? "" : resolvedPlan.planVersion(),
                    blueprint.name() == null ? "" : blueprint.name(),
                    blueprint.version() == null ? "" : blueprint.version()
                )
            );
        }
    }

    private java.util.List<?> readJsonRouteTemplates(AgentBlueprint blueprint) {
        try {
            return (java.util.List<?>) blueprint.getClass().getMethod("jsonRouteTemplates").invoke(blueprint);
        } catch (Exception ignored) {
            return java.util.List.of();
        }
    }
}
