package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Properties;
import org.apache.camel.main.Main;

final class SampleAdminMcpBindings {

    private SampleAdminMcpBindings() {
    }

    static void bindIfMissing(Main main, String applicationYamlPath) {
        bindIfMissing(main, "agUiPlanVersionSelector", new AgUiPlanVersionSelectorProcessor());
        bindIfMissing(main, "sampleAgentSessionInvokeProcessor", newAgentSessionInvokeProcessor());
        if (main.lookup("mcpError", Object.class) != null) {
            return;
        }
        try {
            Object objectMapper = lookup(main, "objectMapper");
            ensureAuditProcessors(main, applicationYamlPath, (ObjectMapper) objectMapper);
            Object auditTrailSearchProcessor = required(main, "auditTrailSearchProcessor");
            Object auditConversationListProcessor = required(main, "auditConversationListProcessor");
            Object auditConversationViewProcessor = required(main, "auditConversationViewProcessor");
            Object auditConversationSessionDataProcessor = required(main, "auditConversationSessionDataProcessor");
            Object auditConversationAgentMessageProcessor = required(main, "auditConversationAgentMessageProcessor");
            Object auditAgentBlueprintProcessor = required(main, "auditAgentBlueprintProcessor");
            Object auditAgentCatalogProcessor = required(main, "auditAgentCatalogProcessor");
            Object runtimeAuditGranularityProcessor = required(main, "runtimeAuditGranularityProcessor");
            Object runtimeResourceRefreshProcessor = required(main, "runtimeResourceRefreshProcessor");
            Object runtimeConversationPersistenceProcessor = required(main, "runtimeConversationPersistenceProcessor");
            Object runtimeConversationCloseProcessor = required(main, "runtimeConversationCloseProcessor");
            Object runtimePurgePreviewProcessor = required(main, "runtimePurgePreviewProcessor");

            Object requestSizeGuardProcessor = newInstance("io.dscope.camel.mcp.processor.McpRequestSizeGuardProcessor");
            Object httpValidatorProcessor = newInstance("io.dscope.camel.mcp.processor.McpHttpValidatorProcessor");
            Object rateLimitProcessor = newInstance("io.dscope.camel.mcp.processor.McpRateLimitProcessor");
            Object jsonRpcEnvelopeProcessor = newInstance("io.dscope.camel.mcp.processor.McpJsonRpcEnvelopeProcessor");
            Object initializeProcessor = newInstance("io.dscope.camel.mcp.processor.McpInitializeProcessor");
            Object pingProcessor = newInstance("io.dscope.camel.mcp.processor.McpPingProcessor");
            Object notificationsInitializedProcessor = newInstance("io.dscope.camel.mcp.processor.McpNotificationsInitializedProcessor");
            Object notificationProcessor = newInstance("io.dscope.camel.mcp.processor.McpNotificationProcessor");
            Object notificationAckProcessor = newInstance("io.dscope.camel.mcp.processor.McpNotificationAckProcessor");
            Object errorProcessor = newInstance("io.dscope.camel.mcp.processor.McpErrorProcessor");
            Object streamProcessor = newInstance("io.dscope.camel.mcp.processor.McpStreamProcessor");
            Object healthStatusProcessor = newInstance(
                "io.dscope.camel.mcp.processor.McpHealthStatusProcessor",
                new Class<?>[] { rateLimitProcessor.getClass().getInterfaces().length > 0 ? rateLimitProcessor.getClass().getInterfaces()[0] : rateLimitProcessor.getClass() },
                new Object[] { rateLimitProcessor }
            );

            Object uiSessionRegistry = newInstance("io.dscope.camel.mcp.service.McpUiSessionRegistry");
            uiSessionRegistry.getClass().getMethod("start").invoke(uiSessionRegistry);
            Object uiInitializeProcessor = newInstance("io.dscope.camel.mcp.processor.McpUiInitializeProcessor",
                new Class<?>[] { uiSessionRegistry.getClass() },
                new Object[] { uiSessionRegistry });
            Object uiMessageProcessor = newInstance("io.dscope.camel.mcp.processor.McpUiMessageProcessor",
                new Class<?>[] { uiSessionRegistry.getClass() },
                new Object[] { uiSessionRegistry });
            Object uiUpdateModelContextProcessor = newInstance("io.dscope.camel.mcp.processor.McpUiUpdateModelContextProcessor",
                new Class<?>[] { uiSessionRegistry.getClass() },
                new Object[] { uiSessionRegistry });
            Object uiToolsCallProcessor = newInstance("io.dscope.camel.mcp.processor.McpUiToolsCallProcessor",
                new Class<?>[] { uiSessionRegistry.getClass() },
                new Object[] { uiSessionRegistry });
            Object uiToolsCallPostProcessor = newInstance("io.dscope.camel.mcp.processor.McpUiToolsCallPostProcessor",
                new Class<?>[] { uiSessionRegistry.getClass() },
                new Object[] { uiSessionRegistry });

            Object toolsListProcessor = newInstance("io.dscope.camel.agent.audit.mcp.AuditMcpToolsListProcessor");
            Object toolsCallProcessor = newInstance(
                "io.dscope.camel.agent.audit.mcp.AuditMcpToolsCallProcessor",
                new Class<?>[] {
                    ObjectMapper.class,
                    auditTrailSearchProcessor.getClass(),
                    auditConversationListProcessor.getClass(),
                    auditConversationViewProcessor.getClass(),
                    auditConversationSessionDataProcessor.getClass(),
                    auditConversationAgentMessageProcessor.getClass(),
                    auditAgentBlueprintProcessor.getClass(),
                    auditAgentCatalogProcessor.getClass(),
                    runtimeAuditGranularityProcessor.getClass(),
                    runtimeResourceRefreshProcessor.getClass(),
                    runtimeConversationPersistenceProcessor.getClass(),
                    runtimeConversationCloseProcessor.getClass(),
                    runtimePurgePreviewProcessor.getClass()
                },
                new Object[] {
                    objectMapper,
                    auditTrailSearchProcessor,
                    auditConversationListProcessor,
                    auditConversationViewProcessor,
                    auditConversationSessionDataProcessor,
                    auditConversationAgentMessageProcessor,
                    auditAgentBlueprintProcessor,
                    auditAgentCatalogProcessor,
                    runtimeAuditGranularityProcessor,
                    runtimeResourceRefreshProcessor,
                    runtimeConversationPersistenceProcessor,
                    runtimeConversationCloseProcessor,
                    runtimePurgePreviewProcessor
                }
            );
            Object resourcesListProcessor = newInstance("io.dscope.camel.mcp.processor.McpResourcesListProcessor");
            Object resourcesReadProcessor = newInstance("io.dscope.camel.mcp.processor.McpResourcesReadProcessor");

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
        } catch (Exception e) {
            throw new IllegalStateException("Failed to bind sample MCP admin fallback processors", e);
        }
    }

    private static void ensureAuditProcessors(Main main, String applicationYamlPath, ObjectMapper objectMapper) throws Exception {
        Object persistenceFacade = required(main, "persistenceFacade");
        Object planSelectionResolver = lookupFromRegistry(main, "agentPlanSelectionResolver");
        if (planSelectionResolver == null) {
            planSelectionResolver = newInstance(
                "io.dscope.camel.agent.runtime.AgentPlanSelectionResolver",
                new Class<?>[] { Object.class, ObjectMapper.class },
                new Object[] { persistenceFacade, objectMapper }
            );
            main.bind("agentPlanSelectionResolver", planSelectionResolver);
        }

        Object conversationArchiveService = lookupFromRegistry(main, "conversationArchiveService");
        if (conversationArchiveService == null) {
            conversationArchiveService = newInstance(
                "io.dscope.camel.agent.runtime.ConversationArchiveService",
                new Class<?>[] { Object.class, ObjectMapper.class, boolean.class },
                new Object[] { persistenceFacade, objectMapper, true }
            );
            main.bind("conversationArchiveService", conversationArchiveService);
        }

        Object runtimeControlState = lookupFromRegistry(main, "runtimeControlState");
        if (runtimeControlState == null) {
            runtimeControlState = newInstance(
                "io.dscope.camel.agent.runtime.RuntimeControlState",
                new Class<?>[] { Object.class },
                new Object[] { loadGranularity(applicationYamlPath) }
            );
            main.bind("runtimeControlState", runtimeControlState);
        }

        Properties properties = loadProperties(applicationYamlPath);
        String plansConfig = trimToNull(properties.getProperty("agent.agents-config"));
        String blueprintUri = trimToNull(properties.getProperty("agent.blueprint"));

        bindIfMissing(main, "auditTrailSearchProcessor",
            newInstance(
                "io.dscope.camel.agent.audit.AuditTrailSearchProcessor",
                new Class<?>[] { Object.class, ObjectMapper.class, Object.class, String.class, String.class },
                new Object[] { persistenceFacade, objectMapper, planSelectionResolver, plansConfig, blueprintUri }
            ));
        bindIfMissing(main, "auditConversationListProcessor",
            newInstance(
                "io.dscope.camel.agent.audit.AuditConversationListProcessor",
                new Class<?>[] { Object.class, ObjectMapper.class, Object.class, String.class, String.class },
                new Object[] { persistenceFacade, objectMapper, planSelectionResolver, plansConfig, blueprintUri }
            ));
        bindIfMissing(main, "auditConversationViewProcessor",
            newInstance(
                "io.dscope.camel.agent.audit.AuditConversationViewProcessor",
                new Class<?>[] { Object.class, ObjectMapper.class, Object.class, String.class, String.class },
                new Object[] { persistenceFacade, objectMapper, planSelectionResolver, plansConfig, blueprintUri }
            ));
        bindIfMissing(main, "auditConversationSessionDataProcessor",
            newInstance(
                "io.dscope.camel.agent.audit.AuditConversationSessionDataProcessor",
                new Class<?>[] { Object.class, ObjectMapper.class },
                new Object[] { conversationArchiveService, objectMapper }
            ));
        bindIfMissing(main, "auditConversationAgentMessageProcessor",
            newInstance(
                "io.dscope.camel.agent.audit.AuditConversationAgentMessageProcessor",
                new Class<?>[] { Object.class, ObjectMapper.class },
                new Object[] { persistenceFacade, objectMapper }
            ));
        bindIfMissing(main, "auditConversationUsageProcessor",
            newInstance(
                "io.dscope.camel.agent.audit.AuditConversationUsageProcessor",
                new Class<?>[] { Object.class, ObjectMapper.class },
                new Object[] { persistenceFacade, objectMapper }
            ));
        bindIfMissing(main, "runtimeAuditGranularityProcessor",
            newInstance(
                "io.dscope.camel.agent.runtime.RuntimeAuditGranularityProcessor",
                new Class<?>[] { ObjectMapper.class, Object.class },
                new Object[] { objectMapper, runtimeControlState }
            ));
        bindIfMissing(main, "runtimeResourceRefreshProcessor",
            newInstance(
                "io.dscope.camel.agent.runtime.RuntimeResourceRefreshProcessor",
                new Class<?>[] { String.class, Object.class, ObjectMapper.class },
                new Object[] { applicationYamlPath, persistenceFacade, objectMapper }
            ));
        bindIfMissing(main, "runtimeConversationPersistenceProcessor",
            newInstance(
                "io.dscope.camel.agent.runtime.RuntimeConversationPersistenceProcessor",
                new Class<?>[] { ObjectMapper.class, Object.class },
                new Object[] { objectMapper, conversationArchiveService }
            ));
        bindIfMissing(main, "runtimeConversationCloseProcessor",
            newInstance(
                "io.dscope.camel.agent.runtime.RuntimeConversationCloseProcessor",
                new Class<?>[] { ObjectMapper.class, Object.class },
                new Object[] { objectMapper, persistenceFacade }
            ));
        bindIfMissing(main, "runtimePurgePreviewProcessor",
            newInstance(
                "io.dscope.camel.agent.runtime.RuntimePurgePreviewProcessor",
                new Class<?>[] { ObjectMapper.class, Object.class },
                new Object[] { objectMapper, persistenceFacade }
            ));
        bindIfMissing(main, "auditAgentBlueprintProcessor",
            newInstance(
                "io.dscope.camel.agent.audit.AuditAgentBlueprintProcessor",
                new Class<?>[] { Object.class, ObjectMapper.class, Object.class, String.class, String.class },
                new Object[] { persistenceFacade, objectMapper, planSelectionResolver, plansConfig, blueprintUri }
            ));
        bindIfMissing(main, "auditAgentCatalogProcessor",
            newInstance(
                "io.dscope.camel.agent.audit.AuditAgentCatalogProcessor",
                new Class<?>[] { ObjectMapper.class, Object.class, String.class, String.class },
                new Object[] { objectMapper, planSelectionResolver, plansConfig, blueprintUri }
            ));
    }

    private static void bindIfMissing(Main main, String name, Object value) {
        if (lookupFromRegistry(main, name) == null && main.lookup(name, Object.class) == null) {
            main.bind(name, value);
        }
    }

    private static Object loadGranularity(String applicationYamlPath) {
        try {
            Properties properties = loadProperties(applicationYamlPath);
            Class<?> enumType = Class.forName("io.dscope.camel.agent.model.AuditGranularity");
            return enumType.getMethod("from", String.class)
                .invoke(null, properties.getProperty("agent.audit.granularity", "debug"));
        } catch (Exception ignored) {
            try {
                Class<?> enumType = Class.forName("io.dscope.camel.agent.model.AuditGranularity");
                return Enum.valueOf((Class<Enum>) enumType.asSubclass(Enum.class), "DEBUG");
            } catch (Exception nested) {
                return null;
            }
        }
    }

    private static Properties loadProperties(String applicationYamlPath) {
        try {
            Class<?> loader = Class.forName("io.dscope.camel.agent.runtime.ApplicationYamlLoader");
            return (Properties) loader.getMethod("loadFromClasspath", String.class).invoke(null, applicationYamlPath);
        } catch (Exception ignored) {
            return new Properties();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Object lookup(Main main, String name) {
        Object value = main.lookup(name, Object.class);
        return value != null ? value : new ObjectMapper();
    }

    private static Object lookupFromRegistry(Main main, String name) {
        return main.lookup(name, Object.class);
    }

    private static Object required(Main main, String name) {
        Object value = main.lookup(name, Object.class);
        if (value == null) {
            throw new IllegalStateException("Required bean missing after runtime bootstrap: " + name);
        }
        return value;
    }

    private static Object newInstance(String className) throws Exception {
        return Class.forName(className).getDeclaredConstructor().newInstance();
    }

    private static Object newInstance(String className, Class<?>[] parameterTypes, Object[] args) throws Exception {
        Class<?> type = Class.forName(className);
        try {
            return type.getDeclaredConstructor(parameterTypes).newInstance(args);
        } catch (NoSuchMethodException ignored) {
            for (var constructor : type.getDeclaredConstructors()) {
                if (constructor.getParameterCount() != args.length) {
                    continue;
                }
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            }
            throw ignored;
        }
    }

    private static Object newAgentSessionInvokeProcessor() {
        try {
            return newInstance("io.dscope.camel.agent.session.AgentSessionInvokeProcessor");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create sample agent session invoke processor", e);
        }
    }
}
