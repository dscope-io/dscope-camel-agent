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
    public ChatMemoryRepository chatMemoryRepository(AgentStarterProperties properties, ObjectMapper objectMapper) {
        Properties config = new Properties();
        config.setProperty("camel.persistence.enabled", "true");
        config.setProperty("camel.persistence.backend", normalizePersistenceBackend(properties.getPersistenceMode()));
        config.setProperty("agent.audit.granularity", properties.getAuditGranularity());
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
    public AiModelClient aiModelClient(ObjectMapper objectMapper) {
        return new SpringAiModelClient(new NoopSpringAiChatGateway(), objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public PersistenceFacade persistenceFacade(AgentStarterProperties properties, ObjectMapper objectMapper) {
        Properties config = new Properties();
        config.setProperty("camel.persistence.enabled", "true");
        config.setProperty("camel.persistence.backend", normalizePersistenceBackend(properties.getPersistenceMode()));
        config.setProperty("agent.audit.granularity", properties.getAuditGranularity());
        copyIfPresent(config, "agent.audit.backend", properties.getAuditPersistenceBackend());
        copyIfPresent(config, "agent.audit.jdbc.url", properties.getAuditJdbcUrl());
        copyIfPresent(config, "agent.audit.jdbc.username", properties.getAuditJdbcUsername());
        copyIfPresent(config, "agent.audit.jdbc.password", properties.getAuditJdbcPassword());
        copyIfPresent(config, "agent.audit.jdbc.driver-class-name", properties.getAuditJdbcDriverClassName());
        return DscopePersistenceFactory.create(config, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentPlanSelectionResolver agentPlanSelectionResolver(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
        return new AgentPlanSelectionResolver(persistenceFacade, objectMapper);
    }

    private void copyIfPresent(Properties config, String key, String value) {
        if (value != null && !value.isBlank()) {
            config.setProperty(key, value);
        }
    }

    private String normalizePersistenceBackend(String backend) {
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
            properties.getTaskClaimLeaseSeconds()
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
