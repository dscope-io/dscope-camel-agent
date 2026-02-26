package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.agui.AgentAgUiPreRunTextProcessor;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.kernel.StaticAiModelClient;
import io.dscope.camel.agent.realtime.OpenAiRealtimeRelayClient;
import io.dscope.camel.agent.realtime.RealtimeBrowserSessionInitProcessor;
import io.dscope.camel.agent.realtime.RealtimeBrowserSessionRegistry;
import io.dscope.camel.agent.realtime.RealtimeBrowserTokenProcessor;
import io.dscope.camel.agent.realtime.RealtimeEventProcessor;
import io.dscope.camel.agent.realtime.sip.SipCallEndProcessor;
import io.dscope.camel.agent.realtime.sip.SipSessionInitEnvelopeProcessor;
import io.dscope.camel.agent.realtime.sip.SipTranscriptFinalProcessor;
import io.dscope.camel.agent.service.AuditAgentBlueprintProcessor;
import io.dscope.camel.agent.service.AuditConversationListProcessor;
import io.dscope.camel.agent.service.AuditTrailSearchProcessor;
import io.dscope.camel.agent.service.AuditTrailService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Properties;
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

        PersistenceFacade persistenceFacade = existingPersistenceFacade(main);
        if (persistenceFacade == null) {
            persistenceFacade = createPersistenceFacade(properties, objectMapper);
            main.bind("persistenceFacade", persistenceFacade);
        }
        String blueprintUri = properties.getProperty("agent.blueprint", "classpath:agents/support/agent.md");
        main.bind("auditTrailService", new AuditTrailService(persistenceFacade, objectMapper));
        main.bind("auditTrailSearchProcessor", new AuditTrailSearchProcessor(persistenceFacade, objectMapper, blueprintUri));
        main.bind("auditConversationListProcessor", new AuditConversationListProcessor(persistenceFacade, objectMapper, blueprintUri));
        main.bind("runtimeResourceRefreshProcessor", new RuntimeResourceRefreshProcessor(applicationYamlPath, persistenceFacade, objectMapper));
        main.bind("runtimePurgePreviewProcessor", new RuntimePurgePreviewProcessor(objectMapper, persistenceFacade));
        main.bind("runtimeConversationCloseProcessor", new RuntimeConversationCloseProcessor(objectMapper, persistenceFacade));
        main.bind(
            "auditAgentBlueprintProcessor",
            new AuditAgentBlueprintProcessor(
                persistenceFacade,
                objectMapper,
                blueprintUri
            )
        );
        bindAiModelClientIfConfigured(main, properties, objectMapper);
        bindOptionalAgUiAndRealtime(main, properties);

        boolean agentRoutesEnabled = Boolean.parseBoolean(properties.getProperty("agent.runtime.agent-routes-enabled", "true"));
        if (agentRoutesEnabled) {
            main.configure().addRoutesBuilder(new AgentRuntimeRouteBuilder(AgentRuntimeProperties.from(properties)));
            LOGGER.info("Agent runtime route builder enabled");
        } else {
            LOGGER.info("Agent runtime route builder disabled by configuration");
        }
        LOGGER.info("Agent runtime bootstrap completed");
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
        } catch (Exception ignored) {
            LOGGER.info("Agent runtime persistence selected: in-memory fallback");
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

    private static void bindOptionalAgUiAndRealtime(Main main, Properties properties) {
        if (booleanPropertyWithAliases(properties, true,
            "agent.runtime.agui.bind-default-beans",
            "agent.runtime.agui.bindDefaultBeans"
        )) {
            bindAgUiDefaultsIfAvailable(main);
        }

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
                "agent:default?blueprint={{agent.blueprint}}"
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
