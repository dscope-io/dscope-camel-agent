package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.a2a.AgentA2AProtocolSupport;
import io.dscope.camel.agent.agui.AgentAgUiPreRunTextProcessor;
import io.dscope.camel.agent.audit.AuditAgentBlueprintProcessor;
import io.dscope.camel.agent.audit.AuditAgentCatalogProcessor;
import io.dscope.camel.agent.audit.AuditConversationAgentMessageProcessor;
import io.dscope.camel.agent.audit.AuditConversationListProcessor;
import io.dscope.camel.agent.audit.AuditConversationSipProcessor;
import io.dscope.camel.agent.audit.AuditConversationViewProcessor;
import io.dscope.camel.agent.audit.AuditConversationSessionDataProcessor;
import io.dscope.camel.agent.audit.AuditConversationUsageProcessor;
import io.dscope.camel.agent.audit.AuditTrailSearchProcessor;
import io.dscope.camel.agent.audit.AuditTrailService;
import io.dscope.camel.agent.audit.mcp.AuditMcpToolsCallProcessor;
import io.dscope.camel.agent.audit.mcp.AuditMcpToolsListProcessor;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.diagnostics.AgUiParamsTraceProcessor;
import io.dscope.camel.agent.diagnostics.DelegatingTraceProcessor;
import io.dscope.camel.agent.diagnostics.PayloadTraceProcessor;
import io.dscope.camel.agent.diagnostics.ResponseTraceProcessor;
import io.dscope.camel.agent.diagnostics.TracingConversationArchiveService;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.kernel.StaticAiModelClient;
import io.dscope.camel.agent.model.AuditGranularity;
import io.dscope.camel.agent.realtime.OpenAiRealtimeRelayClient;
import io.dscope.camel.agent.realtime.RealtimeBrowserSessionInitProcessor;
import io.dscope.camel.agent.realtime.RealtimeBrowserSessionRegistry;
import io.dscope.camel.agent.realtime.RealtimeBrowserTokenProcessor;
import io.dscope.camel.agent.realtime.RealtimeEventProcessor;
import io.dscope.camel.agent.realtime.sip.SipCallEndProcessor;
import io.dscope.camel.agent.realtime.sip.SipSessionInitEnvelopeProcessor;
import io.dscope.camel.agent.realtime.sip.SipTranscriptFinalProcessor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import io.dscope.camel.mcp.processor.McpErrorProcessor;
import io.dscope.camel.mcp.processor.McpHealthStatusProcessor;
import io.dscope.camel.mcp.processor.McpHttpValidatorProcessor;
import io.dscope.camel.mcp.processor.McpInitializeProcessor;
import io.dscope.camel.mcp.processor.McpJsonRpcEnvelopeProcessor;
import io.dscope.camel.mcp.processor.McpNotificationAckProcessor;
import io.dscope.camel.mcp.processor.McpNotificationProcessor;
import io.dscope.camel.mcp.processor.McpNotificationsInitializedProcessor;
import io.dscope.camel.mcp.processor.McpPingProcessor;
import io.dscope.camel.mcp.processor.McpRateLimitProcessor;
import io.dscope.camel.mcp.processor.McpRequestSizeGuardProcessor;
import io.dscope.camel.mcp.processor.McpResourcesListProcessor;
import io.dscope.camel.mcp.processor.McpResourcesReadProcessor;
import io.dscope.camel.mcp.processor.McpStreamProcessor;
import io.dscope.camel.mcp.processor.McpUiInitializeProcessor;
import io.dscope.camel.mcp.processor.McpUiMessageProcessor;
import io.dscope.camel.mcp.processor.McpUiToolsCallPostProcessor;
import io.dscope.camel.mcp.processor.McpUiToolsCallProcessor;
import io.dscope.camel.mcp.processor.McpUiUpdateModelContextProcessor;
import io.dscope.camel.mcp.service.McpUiSessionRegistry;
import org.apache.camel.main.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentRuntimeBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntimeBootstrap.class);

    private AgentRuntimeBootstrap() {
    }

    public static void bootstrap(Main main, String applicationYamlPath) throws Exception {
        Properties properties = effectiveProperties(applicationYamlPath);
        properties = RuntimeResourceBootstrapper.resolve(properties);
        properties.forEach((key, value) -> main.addInitialProperty(String.valueOf(key), String.valueOf(value)));
        LOGGER.info("Agent runtime bootstrap started: applicationYamlPath={}, propertiesLoaded={}",
            applicationYamlPath,
            properties.size());

        String routesIncludePattern = properties.getProperty("agent.runtime.routes-include-pattern");
        if (routesIncludePattern != null && !routesIncludePattern.isBlank()) {
            main.configure().withRoutesIncludePattern(routesIncludePattern);
            LOGGER.info("Agent runtime routes include pattern applied: {}", routesIncludePattern);
        }

        ObjectMapper objectMapper = existingObjectMapper(main);
        main.bind("objectMapper", objectMapper);
        TicketLifecycleProcessor ticketLifecycleProcessor = null;
        if (main.getCamelContext() != null && main.getCamelContext().getRegistry() != null) {
            ticketLifecycleProcessor = main.getCamelContext()
                .getRegistry()
                .lookupByNameAndType("ticketLifecycleProcessor", TicketLifecycleProcessor.class);
        }
        if (ticketLifecycleProcessor == null) {
            main.bind("ticketLifecycleProcessor", new TicketLifecycleProcessor(objectMapper));
        }

        PersistenceFacade persistenceFacade = existingPersistenceFacade(main);
        if (persistenceFacade == null) {
            persistenceFacade = createPersistenceFacade(properties, objectMapper);
        }
        persistenceFacade = maybeWrapAsyncEventPersistence(properties, persistenceFacade, "main");
        main.bind("persistenceFacade", persistenceFacade);

        boolean diagnosticsTraceEnabled = booleanPropertyWithAliases(properties, true,
            "agent.diagnostics.trace.enabled",
            "agent.diagnostics.traceEnabled"
        );

        ConversationArchiveService conversationArchiveService = createConversationArchiveService(properties, objectMapper);
        if (diagnosticsTraceEnabled) {
            conversationArchiveService = new TracingConversationArchiveService(conversationArchiveService, true, objectMapper);
        }
        main.bind("conversationArchiveService", conversationArchiveService);
        String plansConfig = properties.getProperty("agent.agents-config");
        String blueprintUri = properties.getProperty("agent.blueprint");
        AgentPlanSelectionResolver planSelectionResolver = new AgentPlanSelectionResolver(persistenceFacade, objectMapper);
        main.bind("agentPlanSelectionResolver", planSelectionResolver);

        RuntimeControlState runtimeControlState = new RuntimeControlState(
            AuditGranularity.from(properties.getProperty("agent.audit.granularity", "debug"))
        );
        main.bind("runtimeControlState", runtimeControlState);
        AuditTrailSearchProcessor auditTrailSearchProcessor =
            new AuditTrailSearchProcessor(persistenceFacade, objectMapper, planSelectionResolver, plansConfig, blueprintUri);
        AuditConversationListProcessor auditConversationListProcessor =
            new AuditConversationListProcessor(persistenceFacade, objectMapper, planSelectionResolver, plansConfig, blueprintUri);
        AuditConversationViewProcessor auditConversationViewProcessor = new AuditConversationViewProcessor(
            persistenceFacade,
            objectMapper,
            planSelectionResolver,
            plansConfig,
            blueprintUri
        );
        AuditConversationSipProcessor auditConversationSipProcessor = new AuditConversationSipProcessor(persistenceFacade, objectMapper);
        AuditConversationAgentMessageProcessor auditConversationAgentMessageProcessor = new AuditConversationAgentMessageProcessor(persistenceFacade, objectMapper);
        AuditConversationUsageProcessor auditConversationUsageProcessor = new AuditConversationUsageProcessor(persistenceFacade, objectMapper);
        AuditConversationSessionDataProcessor auditConversationSessionDataProcessor =
            new AuditConversationSessionDataProcessor(conversationArchiveService, objectMapper);
        RuntimeAuditGranularityProcessor runtimeAuditGranularityProcessor =
            new RuntimeAuditGranularityProcessor(objectMapper, runtimeControlState);
        RuntimeConversationPersistenceProcessor runtimeConversationPersistenceProcessor =
            new RuntimeConversationPersistenceProcessor(objectMapper, conversationArchiveService);
        RuntimeResourceRefreshProcessor runtimeResourceRefreshProcessor = new RuntimeResourceRefreshProcessor(applicationYamlPath, persistenceFacade, objectMapper);
        RuntimePurgePreviewProcessor runtimePurgePreviewProcessor = new RuntimePurgePreviewProcessor(objectMapper, persistenceFacade);
        RuntimeConversationCloseProcessor runtimeConversationCloseProcessor = new RuntimeConversationCloseProcessor(objectMapper, persistenceFacade);
        AuditAgentBlueprintProcessor auditAgentBlueprintProcessor = new AuditAgentBlueprintProcessor(
            persistenceFacade,
            objectMapper,
            planSelectionResolver,
            plansConfig,
            blueprintUri
        );
        AuditAgentCatalogProcessor auditAgentCatalogProcessor = new AuditAgentCatalogProcessor(
            objectMapper,
            planSelectionResolver,
            plansConfig,
            blueprintUri
        );

        Object auditConversationViewBinding = auditConversationViewProcessor;
        Object auditConversationSessionDataBinding = auditConversationSessionDataProcessor;
        if (diagnosticsTraceEnabled) {
            auditConversationViewBinding = new DelegatingTraceProcessor(
                "auditConversationViewProcessor",
                auditConversationViewProcessor,
                true
            );
            auditConversationSessionDataBinding = new DelegatingTraceProcessor(
                "auditConversationSessionDataProcessor",
                auditConversationSessionDataProcessor,
                true
            );
        }

        main.bind("auditTrailService", new AuditTrailService(persistenceFacade, objectMapper));
        main.bind("auditTrailSearchProcessor", auditTrailSearchProcessor);
        main.bind("auditConversationListProcessor", auditConversationListProcessor);
        main.bind("auditConversationViewProcessor", auditConversationViewBinding);
        main.bind("auditConversationSipProcessor", auditConversationSipProcessor);
        main.bind("auditConversationAgentMessageProcessor", auditConversationAgentMessageProcessor);
        main.bind("auditConversationUsageProcessor", auditConversationUsageProcessor);
        main.bind("auditConversationSessionDataProcessor", auditConversationSessionDataBinding);
        main.bind("runtimeAuditGranularityProcessor", runtimeAuditGranularityProcessor);
        main.bind("runtimeConversationPersistenceProcessor", runtimeConversationPersistenceProcessor);
        main.bind("runtimeResourceRefreshProcessor", runtimeResourceRefreshProcessor);
        main.bind("runtimePurgePreviewProcessor", runtimePurgePreviewProcessor);
        main.bind("runtimeConversationCloseProcessor", runtimeConversationCloseProcessor);
        main.bind("auditAgentBlueprintProcessor", auditAgentBlueprintProcessor);
        main.bind("auditAgentCatalogProcessor", auditAgentCatalogProcessor);

        bindMcpProcessors(
            main,
            objectMapper,
            auditTrailSearchProcessor,
            auditConversationListProcessor,
            auditConversationViewProcessor,
            auditConversationSipProcessor,
            auditConversationSessionDataProcessor,
            auditConversationAgentMessageProcessor,
            auditAgentBlueprintProcessor,
            auditAgentCatalogProcessor,
            runtimeAuditGranularityProcessor,
            runtimeResourceRefreshProcessor,
            runtimeConversationPersistenceProcessor,
            runtimeConversationCloseProcessor,
            runtimePurgePreviewProcessor
        );

        bindAiModelClientIfConfigured(main, properties, objectMapper);
        bindOptionalAgUiRealtimeAndA2a(main, properties, persistenceFacade, planSelectionResolver, objectMapper);

        boolean agentRoutesEnabled = Boolean.parseBoolean(properties.getProperty("agent.runtime.agent-routes-enabled", "true"));
        if (agentRoutesEnabled) {
            main.configure().addRoutesBuilder(new AgentRuntimeRouteBuilder(AgentRuntimeProperties.from(properties)));
            LOGGER.info("Agent runtime route builder enabled");
        } else {
            LOGGER.info("Agent runtime route builder disabled by configuration");
        }
        A2ARuntimeProperties a2aRuntimeProperties = A2ARuntimeProperties.from(properties);
        if (a2aRuntimeProperties.enabled()) {
            main.configure().addRoutesBuilder(new AgentA2ARuntimeRouteBuilder(a2aRuntimeProperties));
            LOGGER.info("Agent runtime A2A route builder enabled");
        } else {
            LOGGER.info("Agent runtime A2A route builder disabled by configuration");
        }
        LOGGER.info("Agent runtime bootstrap completed");
    }

    private static void bindMcpProcessors(Main main,
                                          ObjectMapper objectMapper,
                                          AuditTrailSearchProcessor auditTrailSearchProcessor,
                                          AuditConversationListProcessor auditConversationListProcessor,
                                          AuditConversationViewProcessor auditConversationViewProcessor,
                                          AuditConversationSipProcessor auditConversationSipProcessor,
                                          AuditConversationSessionDataProcessor auditConversationSessionDataProcessor,
                                          AuditConversationAgentMessageProcessor auditConversationAgentMessageProcessor,
                                          AuditAgentBlueprintProcessor auditAgentBlueprintProcessor,
                                          AuditAgentCatalogProcessor auditAgentCatalogProcessor,
                                          RuntimeAuditGranularityProcessor runtimeAuditGranularityProcessor,
                                          RuntimeResourceRefreshProcessor runtimeResourceRefreshProcessor,
                                          RuntimeConversationPersistenceProcessor runtimeConversationPersistenceProcessor,
                                          RuntimeConversationCloseProcessor runtimeConversationCloseProcessor,
                                          RuntimePurgePreviewProcessor runtimePurgePreviewProcessor) {
        McpRequestSizeGuardProcessor requestSizeGuardProcessor = new McpRequestSizeGuardProcessor();
        McpHttpValidatorProcessor httpValidatorProcessor = new McpHttpValidatorProcessor();
        McpRateLimitProcessor rateLimitProcessor = new McpRateLimitProcessor();
        McpJsonRpcEnvelopeProcessor jsonRpcEnvelopeProcessor = new McpJsonRpcEnvelopeProcessor();
        McpInitializeProcessor initializeProcessor = new McpInitializeProcessor();
        McpPingProcessor pingProcessor = new McpPingProcessor();
        McpNotificationsInitializedProcessor notificationsInitializedProcessor = new McpNotificationsInitializedProcessor();
        McpNotificationProcessor notificationProcessor = new McpNotificationProcessor();
        McpNotificationAckProcessor notificationAckProcessor = new McpNotificationAckProcessor();
        McpErrorProcessor errorProcessor = new McpErrorProcessor();
        McpStreamProcessor streamProcessor = new McpStreamProcessor();
        McpHealthStatusProcessor healthStatusProcessor = new McpHealthStatusProcessor(rateLimitProcessor);

        McpUiSessionRegistry uiSessionRegistry = new McpUiSessionRegistry();
        uiSessionRegistry.start();
        McpUiInitializeProcessor uiInitializeProcessor = new McpUiInitializeProcessor(uiSessionRegistry);
        McpUiMessageProcessor uiMessageProcessor = new McpUiMessageProcessor(uiSessionRegistry);
        McpUiUpdateModelContextProcessor uiUpdateModelContextProcessor = new McpUiUpdateModelContextProcessor(uiSessionRegistry);
        McpUiToolsCallProcessor uiToolsCallProcessor = new McpUiToolsCallProcessor(uiSessionRegistry);
        McpUiToolsCallPostProcessor uiToolsCallPostProcessor = new McpUiToolsCallPostProcessor(uiSessionRegistry);

        AuditMcpToolsListProcessor toolsListProcessor = new AuditMcpToolsListProcessor();
        AuditMcpToolsCallProcessor toolsCallProcessor = new AuditMcpToolsCallProcessor(
            objectMapper,
            auditTrailSearchProcessor,
            auditConversationListProcessor,
            auditConversationViewProcessor,
            auditConversationSipProcessor,
            auditConversationSessionDataProcessor,
            auditConversationAgentMessageProcessor,
            auditAgentBlueprintProcessor,
            auditAgentCatalogProcessor,
            runtimeAuditGranularityProcessor,
            runtimeResourceRefreshProcessor,
            runtimeConversationPersistenceProcessor,
            runtimeConversationCloseProcessor,
            runtimePurgePreviewProcessor
        );
        McpResourcesListProcessor resourcesListProcessor = new McpResourcesListProcessor();
        McpResourcesReadProcessor resourcesReadProcessor = new McpResourcesReadProcessor();

        main.bind("mcpRequestSizeGuard", requestSizeGuardProcessor);
        main.bind("mcpHttpValidator", httpValidatorProcessor);
        main.bind("mcpRateLimit", rateLimitProcessor);
        main.bind("mcpJsonRpcEnvelope", jsonRpcEnvelopeProcessor);
        main.bind("mcpInitialize", initializeProcessor);
        main.bind("mcpPing", pingProcessor);
        main.bind("mcpNotificationsInitialized", notificationsInitializedProcessor);
        main.bind("mcpNotification", notificationProcessor);
        main.bind("mcpNotificationAck", notificationAckProcessor);
        main.bind("mcpError", errorProcessor);
        main.bind("mcpStream", streamProcessor);
        main.bind("mcpHealthStatus", healthStatusProcessor);
        main.bind("mcpUiSessionRegistry", uiSessionRegistry);
        main.bind("mcpUiInitialize", uiInitializeProcessor);
        main.bind("mcpUiMessage", uiMessageProcessor);
        main.bind("mcpUiUpdateModelContext", uiUpdateModelContextProcessor);
        main.bind("mcpUiToolsCall", uiToolsCallProcessor);
        main.bind("mcpUiToolsCallPost", uiToolsCallPostProcessor);
        main.bind("mcpToolsList", toolsListProcessor);
        main.bind("mcpToolsCall", toolsCallProcessor);
        main.bind("mcpResourcesList", resourcesListProcessor);
        main.bind("mcpResourcesRead", resourcesReadProcessor);
    }

    private static ObjectMapper existingObjectMapper(Main main) {
        ObjectMapper mapper = main.lookup("objectMapper", ObjectMapper.class);
        return mapper != null ? mapper : new ObjectMapper();
    }

    private static PersistenceFacade existingPersistenceFacade(Main main) {
        return main.lookup("persistenceFacade", PersistenceFacade.class);
    }

    private static PersistenceFacade createPersistenceFacade(Properties properties, ObjectMapper objectMapper) {
        try {
            Class<?> factoryType = Class.forName("io.dscope.camel.agent.persistence.dscope.DscopePersistenceFactory");
            Method create = factoryType.getMethod("create", Properties.class, ObjectMapper.class);
            PersistenceFacade persistence = (PersistenceFacade) create.invoke(null, properties, objectMapper);
            LOGGER.info("Agent runtime persistence selected: dscope factory");
            return persistence;
        } catch (Exception failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String rootMessage = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            LOGGER.warn("Agent runtime persistence selected: in-memory fallback (reason: {})", rootMessage);
            LOGGER.debug("Agent runtime persistence bootstrap failure details", failure);
            return new InMemoryPersistenceFacade();
        }
    }

    private static ConversationArchiveService createConversationArchiveService(Properties properties, ObjectMapper objectMapper) {
        boolean enabled = Boolean.parseBoolean(properties.getProperty("agent.conversation.persistence.enabled", "false"));
        PersistenceFacade archivePersistence = createConversationArchivePersistence(properties, objectMapper);
        archivePersistence = maybeWrapAsyncEventPersistence(properties, archivePersistence, "archive");
        LOGGER.info("Agent runtime conversation persistence initialized: enabled={}", enabled);
        return new ConversationArchiveService(archivePersistence, objectMapper, enabled);
    }

    private static PersistenceFacade maybeWrapAsyncEventPersistence(Properties properties,
                                                                   PersistenceFacade persistenceFacade,
                                                                   String name) {
        if (persistenceFacade == null || persistenceFacade instanceof AsyncEventPersistenceFacade) {
            return persistenceFacade;
        }
        boolean enabled = Boolean.parseBoolean(properties.getProperty("agent.audit.async.enabled", "false"));
        if (!enabled) {
            return persistenceFacade;
        }
        int queueCapacity = intProperty(properties, "agent.audit.async.queue-capacity", 4096);
        long retryDelayMs = longProperty(properties, "agent.audit.async.retry-delay-ms", 250L);
        long shutdownTimeoutMs = longProperty(properties, "agent.audit.async.shutdown-timeout-ms", 5000L);
        long metricsLogIntervalMs = longProperty(properties, "agent.audit.async.metrics-log-interval-ms", 30000L);
        AsyncEventPersistenceFacade asyncFacade = new AsyncEventPersistenceFacade(
            persistenceFacade,
            name,
            queueCapacity,
            retryDelayMs,
            shutdownTimeoutMs,
            metricsLogIntervalMs
        );
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("agent-audit-async-shutdown-" + name).unstarted(() -> {
            try {
                asyncFacade.close();
            } catch (RuntimeException shutdownFailure) {
                LOGGER.debug("Async audit facade shutdown failed: name={}", name, shutdownFailure);
            }
        }));
        LOGGER.info("Agent runtime async audit persistence enabled: name={}, queueCapacity={}, retryDelayMs={}, shutdownTimeoutMs={}, metricsLogIntervalMs={}",
            name,
            queueCapacity,
            retryDelayMs,
            shutdownTimeoutMs,
            metricsLogIntervalMs);
        return asyncFacade;
    }

    private static int intProperty(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long longProperty(Properties properties, String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static PersistenceFacade createConversationArchivePersistence(Properties properties, ObjectMapper objectMapper) {
        Map<String, String> mapped = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("agent.conversation.persistence.")) {
                continue;
            }
            String suffix = key.substring("agent.conversation.persistence.".length());
            if (suffix.isBlank() || "enabled".equals(suffix)) {
                continue;
            }
            mapped.put("camel.persistence." + suffix, properties.getProperty(key));
        }

        if (mapped.isEmpty()) {
            return new InMemoryPersistenceFacade();
        }

        Properties archiveProps = new Properties();
        archiveProps.putAll(mapped);
        archiveProps.putIfAbsent("camel.persistence.enabled", "true");
        archiveProps.putIfAbsent("camel.persistence.backend", "jdbc");

        try {
            Class<?> factoryType = Class.forName("io.dscope.camel.agent.persistence.dscope.DscopePersistenceFactory");
            Method create = factoryType.getMethod("create", Properties.class, ObjectMapper.class);
            return (PersistenceFacade) create.invoke(null, archiveProps, objectMapper);
        } catch (Exception failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String rootMessage = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            LOGGER.warn("Conversation archive persistence fallback to in-memory (reason: {})", rootMessage);
            return new InMemoryPersistenceFacade();
        }
    }

    private static Properties effectiveProperties(String applicationYamlPath) throws Exception {
        Properties effective = RuntimePropertyPlaceholderResolver.resolve(ApplicationYamlLoader.loadFromClasspath(applicationYamlPath));
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("agent.") || key.startsWith("camel.")) {
                effective.setProperty(key, System.getProperty(key));
            }
        }
        return effective;
    }

    private static void bindAiModelClientIfConfigured(Main main, Properties properties, ObjectMapper objectMapper) {
        if (main.lookup("aiModelClient", AiModelClient.class) != null) {
            LOGGER.debug("Agent runtime AI model client already bound");
            return;
        }
        String aiMode = properties.getProperty("agent.runtime.ai.mode", "").trim();
        if ("realtime".equalsIgnoreCase(aiMode)) {
            main.bind("aiModelClient", new StaticAiModelClient());
            LOGGER.info("Agent runtime AI model client bound: static realtime mode");
            return;
        }
        if (!"spring-ai".equalsIgnoreCase(aiMode)) {
            LOGGER.debug("Agent runtime AI model client mode not configured for auto-bind: mode={}", aiMode);
            return;
        }
        String gatewayClassName = properties.getProperty("agent.runtime.spring-ai.gateway-class", "").trim();
        try {
            Class<?> springAiGatewayType = Class.forName("io.dscope.camel.agent.springai.SpringAiChatGateway");
            Class<?> springAiModelClientType = Class.forName("io.dscope.camel.agent.springai.SpringAiModelClient");
            Object gateway;
            if (gatewayClassName.isBlank()) {
                gateway = main.lookup("springAiChatGateway", springAiGatewayType);
                if (gateway == null) {
                    gateway = defaultSpringAiGateway(properties);
                    if (gateway == null) {
                        return;
                    }
                }
            } else {
                Class<?> gatewayType = Class.forName(gatewayClassName);
                gateway = instantiateGateway(gatewayType, properties);
            }
            Object aiModelClient = springAiModelClientType
                .getConstructor(springAiGatewayType, ObjectMapper.class)
                .newInstance(gateway, objectMapper);
            main.bind("aiModelClient", aiModelClient);
            LOGGER.info("Agent runtime AI model client bound: spring-ai mode");
        } catch (Exception e) {
            // Optional feature: if Spring AI classes are unavailable, fallback model client will remain in effect.
            System.err.println("Spring AI model client bootstrap skipped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            LOGGER.warn("Agent runtime AI model client bootstrap skipped: {}", e.getMessage());
        }
    }

    private static void bindOptionalAgUiRealtimeAndA2a(Main main,
                                                       Properties properties,
                                                       PersistenceFacade persistenceFacade,
                                                       AgentPlanSelectionResolver planSelectionResolver,
                                                       ObjectMapper objectMapper) {
        if (booleanPropertyWithAliases(properties, true,
            "agent.runtime.agui.bind-default-beans",
            "agent.runtime.agui.bindDefaultBeans"
        )) {
            bindAgUiDefaultsIfAvailable(main);
        }

        bindDiagnosticsIfMissing(main, properties);

        if (booleanPropertyWithAliases(properties, true,
            "agent.runtime.agui.bind-pre-run-processor",
            "agent.runtime.agui.bindPreRunProcessor"
        )) {
            bindAgUiPreRunProcessorIfMissing(main);
        }

        if (booleanPropertyWithAliases(properties, true,
            "agent.runtime.realtime.bind-relay",
            "agent.runtime.realtime.bindRelay"
        )) {
            bindRealtimeRelayIfMissing(main);
        }

        if (booleanPropertyWithAliases(properties, true,
            "agent.runtime.realtime.bind-processor",
            "agent.runtime.realtime.bindProcessor"
        )) {
            String beanName = firstNonBlank(
                properties.getProperty("agent.runtime.realtime.processor-bean-name"),
                properties.getProperty("agent.runtime.realtime.processorBeanName"),
                "supportRealtimeEventProcessor"
            );
            String agentEndpointUri = firstNonBlank(
                properties.getProperty("agent.runtime.realtime.agent-endpoint-uri"),
                properties.getProperty("agent.runtime.realtime.agentEndpointUri"),
                properties.getProperty("agent.realtime.agent-endpoint-uri"),
                properties.getProperty("agent.realtime.agentEndpointUri"),
                "agent:default?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}"
            );
            bindRealtimeProcessorIfMissing(main, beanName, agentEndpointUri);
        }

        if (booleanPropertyWithAliases(properties, true,
            "agent.runtime.realtime.bind-token-processor",
            "agent.runtime.realtime.bindTokenProcessor"
        )) {
            String tokenBeanName = firstNonBlank(
                properties.getProperty("agent.runtime.realtime.token-processor-bean-name"),
                properties.getProperty("agent.runtime.realtime.tokenProcessorBeanName"),
                "supportRealtimeTokenProcessor"
            );
            String initBeanName = firstNonBlank(
                properties.getProperty("agent.runtime.realtime.init-processor-bean-name"),
                properties.getProperty("agent.runtime.realtime.initProcessorBeanName"),
                "supportRealtimeSessionInitProcessor"
            );
            long browserSessionTtlMs = longPropertyWithAliases(properties, 10 * 60 * 1000L,
                "agent.runtime.realtime.browser-session-ttl-ms",
                "agent.runtime.realtime.browserSessionTtlMs");
            boolean preferCoreTokenProcessor = booleanPropertyWithAliases(properties, false,
                "agent.runtime.realtime.prefer-core-token-processor",
                "agent.runtime.realtime.preferCoreTokenProcessor");
            if (preferCoreTokenProcessor
                && isBlank(properties.getProperty("agent.runtime.realtime.require-init-session"))
                && isBlank(properties.getProperty("agent.runtime.realtime.requireInitSession"))) {
                properties.setProperty("agent.runtime.realtime.require-init-session", "true");
                main.addInitialProperty("agent.runtime.realtime.require-init-session", "true");
                LOGGER.info("Agent runtime realtime strict token mode enabled by default because prefer-core-token-processor=true");
            }
            bindRealtimeBrowserSessionSupportIfMissing(
                main,
                initBeanName,
                tokenBeanName,
                browserSessionTtlMs,
                preferCoreTokenProcessor
            );
        }

        if (booleanPropertyWithAliases(properties, false,
            "agent.runtime.sip.bind-processors",
            "agent.runtime.sip.bindProcessors"
        )) {
            String sipInitBeanName = firstNonBlank(
                properties.getProperty("agent.runtime.sip.init-envelope-processor-bean-name"),
                properties.getProperty("agent.runtime.sip.initEnvelopeProcessorBeanName"),
                "supportSipSessionInitEnvelopeProcessor"
            );
            String sipTranscriptBeanName = firstNonBlank(
                properties.getProperty("agent.runtime.sip.transcript-final-processor-bean-name"),
                properties.getProperty("agent.runtime.sip.transcriptFinalProcessorBeanName"),
                "supportSipTranscriptFinalProcessor"
            );
            String sipCallEndBeanName = firstNonBlank(
                properties.getProperty("agent.runtime.sip.call-end-processor-bean-name"),
                properties.getProperty("agent.runtime.sip.callEndProcessorBeanName"),
                "supportSipCallEndProcessor"
            );
            bindSipProcessorsIfMissing(main, sipInitBeanName, sipTranscriptBeanName, sipCallEndBeanName);
        }

        AgentA2AProtocolSupport.bindIfEnabled(
            main,
            properties,
            A2ARuntimeProperties.from(properties),
            persistenceFacade,
            planSelectionResolver,
            objectMapper
        );
    }

    private static void bindAgUiDefaultsIfAvailable(Main main) {
        try {
            Class<?> supportType = Class.forName("io.dscope.camel.agui.AgUiComponentApplicationSupport");
            Object support = supportType.getDeclaredConstructor().newInstance();
            Class<?> beanBinderType = Class.forName("io.dscope.camel.agui.AgUiComponentApplicationSupport$BeanBinder");
            Method bindDefaultBeans = supportType.getMethod("bindDefaultBeans", beanBinderType);
            Object binder = Proxy.newProxyInstance(
                supportType.getClassLoader(),
                new Class<?>[] {beanBinderType},
                (proxy, method, args) -> {
                    if ("bind".equals(method.getName())
                        && args != null
                        && args.length == 2
                        && args[0] != null) {
                        main.bind(String.valueOf(args[0]), args[1]);
                    }
                    return null;
                }
            );
            bindDefaultBeans.invoke(support, binder);
            LOGGER.info("Agent runtime AGUI default beans bound");
        } catch (Exception ignored) {
            // Optional feature: AGUI component classes may not be present in all runtimes.
            LOGGER.debug("Agent runtime AGUI default bean binding skipped: class unavailable or incompatible");
        }
    }

    private static void bindAgUiPreRunProcessorIfMissing(Main main) {
        try {
            String beanName = agUiPreRunBeanName();
            if (beanName == null || beanName.isBlank()) {
                return;
            }
            if (main.lookup(beanName, Object.class) == null) {
                main.bind(beanName, new AgentAgUiPreRunTextProcessor());
                LOGGER.info("Agent runtime AGUI pre-run processor bound: beanName={}", beanName);
            } else {
                LOGGER.debug("Agent runtime AGUI pre-run processor already present: beanName={}", beanName);
            }
        } catch (Exception ignored) {
            // Optional feature: AGUI component classes may not be present in all runtimes.
            LOGGER.debug("Agent runtime AGUI pre-run processor binding skipped: class unavailable or incompatible");
        }
    }

    private static void bindDiagnosticsIfMissing(Main main, Properties properties) {
        boolean enabled = booleanPropertyWithAliases(properties, true,
            "agent.diagnostics.trace.enabled",
            "agent.diagnostics.traceEnabled"
        );

        bindIfMissing(main, "aguiIngressRawTraceProcessor", new PayloadTraceProcessor("AGUI ingress raw request", enabled));
        bindIfMissing(main, "aguiParsedParamsTraceProcessor", new AgUiParamsTraceProcessor(enabled));
        bindIfMissing(main, "aguiAgentResponseTraceProcessor", new ResponseTraceProcessor("AGUI request processor response", enabled));
        bindIfMissing(main, "realtimeIngressRawTraceProcessor", new PayloadTraceProcessor("Realtime ingress raw request", enabled));
        bindIfMissing(main, "realtimeEventResponseTraceProcessor", new ResponseTraceProcessor("Realtime event processor response", enabled));
    }

    private static void bindIfMissing(Main main, String beanName, Object bean) {
        if (beanName == null || beanName.isBlank() || bean == null) {
            return;
        }
        if (main.lookup(beanName, Object.class) != null) {
            LOGGER.debug("Agent runtime bean already present: beanName={}", beanName);
            return;
        }
        main.bind(beanName, bean);
        LOGGER.info("Agent runtime bean bound: beanName={}, type={}", beanName, bean.getClass().getSimpleName());
    }

    private static String agUiPreRunBeanName() {
        try {
            Class<?> supportType = Class.forName("io.dscope.camel.agui.AgUiComponentApplicationSupport");
            Field field = supportType.getField("BEAN_AGENT_PRE_RUN_TEXT_PROCESSOR");
            Object value = field.get(null);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void bindRealtimeRelayIfMissing(Main main) {
        if (main.lookup("openAiRealtimeRelayClient", OpenAiRealtimeRelayClient.class) == null) {
            main.bind("openAiRealtimeRelayClient", new OpenAiRealtimeRelayClient());
            LOGGER.info("Agent runtime realtime relay bound: openAiRealtimeRelayClient");
        } else {
            LOGGER.debug("Agent runtime realtime relay already present: openAiRealtimeRelayClient");
        }
    }

    private static void bindRealtimeProcessorIfMissing(Main main, String beanName, String agentEndpointUri) {
        if (beanName == null || beanName.isBlank()) {
            return;
        }
        if (main.lookup(beanName, Object.class) != null) {
            LOGGER.debug("Agent runtime realtime processor already present: beanName={}", beanName);
            return;
        }
        OpenAiRealtimeRelayClient relayClient = main.lookup("openAiRealtimeRelayClient", OpenAiRealtimeRelayClient.class);
        if (relayClient == null) {
            relayClient = new OpenAiRealtimeRelayClient();
            main.bind("openAiRealtimeRelayClient", relayClient);
        }
        main.bind(beanName, new RealtimeEventProcessor(relayClient, agentEndpointUri));
        LOGGER.info("Agent runtime realtime processor bound: beanName={}, endpointUri={}", beanName, agentEndpointUri);
    }

    private static void bindRealtimeBrowserSessionSupportIfMissing(Main main,
                                                                   String initBeanName,
                                                                   String tokenBeanName,
                                                                   long browserSessionTtlMs,
                                                                   boolean preferCoreTokenProcessor) {
        RealtimeBrowserSessionRegistry registry = main.lookup("supportRealtimeSessionRegistry", RealtimeBrowserSessionRegistry.class);
        if (registry == null) {
            registry = new RealtimeBrowserSessionRegistry(browserSessionTtlMs);
            main.bind("supportRealtimeSessionRegistry", registry);
            LOGGER.info("Agent runtime realtime session registry bound: beanName=supportRealtimeSessionRegistry, ttlMs={}", browserSessionTtlMs);
        }

        if (initBeanName != null && !initBeanName.isBlank()) {
            Object existingInit = main.lookup(initBeanName, Object.class);
            if (existingInit == null || (preferCoreTokenProcessor && !(existingInit instanceof RealtimeBrowserSessionInitProcessor))) {
                main.bind(initBeanName, new RealtimeBrowserSessionInitProcessor(registry));
                if (existingInit == null) {
                    LOGGER.info("Agent runtime realtime init processor bound: beanName={}", initBeanName);
                } else {
                    LOGGER.info("Agent runtime realtime init processor replaced: beanName={}, previousType={}",
                        initBeanName,
                        existingInit.getClass().getName());
                }
            }
        }

        if (tokenBeanName == null || tokenBeanName.isBlank()) {
            return;
        }
        Object existingToken = main.lookup(tokenBeanName, Object.class);
        if (existingToken == null) {
            main.bind(tokenBeanName, new RealtimeBrowserTokenProcessor(registry));
            LOGGER.info("Agent runtime realtime token processor bound: beanName={}", tokenBeanName);
            return;
        }
        if (existingToken instanceof RealtimeBrowserTokenProcessor || !preferCoreTokenProcessor) {
            LOGGER.debug("Agent runtime realtime token processor already present: beanName={}", tokenBeanName);
            return;
        }
        main.bind(tokenBeanName, new RealtimeBrowserTokenProcessor(registry));
        LOGGER.info("Agent runtime realtime token processor replaced: beanName={}, previousType={}",
            tokenBeanName,
            existingToken.getClass().getName());
    }

    private static void bindSipProcessorsIfMissing(Main main,
                                                   String initBeanName,
                                                   String transcriptBeanName,
                                                   String callEndBeanName) {
        if (initBeanName != null && !initBeanName.isBlank() && main.lookup(initBeanName, Object.class) == null) {
            main.bind(initBeanName, new SipSessionInitEnvelopeProcessor());
            LOGGER.info("Agent runtime SIP init envelope processor bound: beanName={}", initBeanName);
        }
        if (transcriptBeanName != null && !transcriptBeanName.isBlank() && main.lookup(transcriptBeanName, Object.class) == null) {
            main.bind(transcriptBeanName, new SipTranscriptFinalProcessor());
            LOGGER.info("Agent runtime SIP transcript processor bound: beanName={}", transcriptBeanName);
        }
        if (callEndBeanName != null && !callEndBeanName.isBlank() && main.lookup(callEndBeanName, Object.class) == null) {
            main.bind(callEndBeanName, new SipCallEndProcessor());
            LOGGER.info("Agent runtime SIP call-end processor bound: beanName={}", callEndBeanName);
        }
    }

    private static Object instantiateGateway(Class<?> gatewayType, Properties properties) throws Exception {
        try {
            Constructor<?> constructor = gatewayType.getDeclaredConstructor(Properties.class);
            return constructor.newInstance(properties);
        } catch (NoSuchMethodException ignored) {
            return gatewayType.getDeclaredConstructor().newInstance();
        }
    }

    private static Object defaultSpringAiGateway(Properties properties) {
        try {
            Class<?> gatewayType = Class.forName("io.dscope.camel.agent.springai.MultiProviderSpringAiChatGateway");
            return instantiateGateway(gatewayType, properties);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean booleanProperty(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private static boolean booleanPropertyWithAliases(Properties properties, boolean defaultValue, String... keys) {
        for (String key : keys) {
            String value = properties.getProperty(key);
            if (value != null && !value.isBlank()) {
                return Boolean.parseBoolean(value);
            }
        }
        return defaultValue;
    }

    private static long longPropertyWithAliases(Properties properties, long defaultValue, String... keys) {
        for (String key : keys) {
            String value = properties.getProperty(key);
            if (value != null && !value.isBlank()) {
                try {
                    return Long.parseLong(value.trim());
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
