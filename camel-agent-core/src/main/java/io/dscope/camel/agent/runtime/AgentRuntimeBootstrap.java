package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.service.AuditTrailService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Properties;
import org.apache.camel.main.Main;

public final class AgentRuntimeBootstrap {

    private AgentRuntimeBootstrap() {
    }

    public static void bootstrap(Main main, String applicationYamlPath) throws Exception {
        Properties properties = effectiveProperties(applicationYamlPath);
        properties.forEach((key, value) -> main.addInitialProperty(String.valueOf(key), String.valueOf(value)));

        String routesIncludePattern = properties.getProperty("agent.runtime.routes-include-pattern");
        if (routesIncludePattern != null && !routesIncludePattern.isBlank()) {
            main.configure().withRoutesIncludePattern(routesIncludePattern);
        }

        ObjectMapper objectMapper = existingObjectMapper(main);
        main.bind("objectMapper", objectMapper);

        PersistenceFacade persistenceFacade = existingPersistenceFacade(main);
        if (persistenceFacade == null) {
            persistenceFacade = createPersistenceFacade(properties, objectMapper);
            main.bind("persistenceFacade", persistenceFacade);
        }
        main.bind("auditTrailService", new AuditTrailService(persistenceFacade, objectMapper));
        bindAiModelClientIfConfigured(main, properties, objectMapper);

        boolean agentRoutesEnabled = Boolean.parseBoolean(properties.getProperty("agent.runtime.agent-routes-enabled", "true"));
        if (agentRoutesEnabled) {
            main.configure().addRoutesBuilder(new AgentRuntimeRouteBuilder(AgentRuntimeProperties.from(properties)));
        }
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
            return (PersistenceFacade) create.invoke(null, properties, objectMapper);
        } catch (Exception ignored) {
            return new InMemoryPersistenceFacade();
        }
    }

    private static Properties effectiveProperties(String applicationYamlPath) throws Exception {
        Properties effective = new Properties();
        effective.putAll(ApplicationYamlLoader.loadFromClasspath(applicationYamlPath));
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("agent.") || key.startsWith("camel.")) {
                effective.setProperty(key, System.getProperty(key));
            }
        }
        return effective;
    }

    private static void bindAiModelClientIfConfigured(Main main, Properties properties, ObjectMapper objectMapper) {
        if (main.lookup("aiModelClient", AiModelClient.class) != null) {
            return;
        }
        String aiMode = properties.getProperty("agent.runtime.ai.mode", "").trim();
        if (!"spring-ai".equalsIgnoreCase(aiMode)) {
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
        } catch (Exception e) {
            // Optional feature: if Spring AI classes are unavailable, fallback model client will remain in effect.
            System.err.println("Spring AI model client bootstrap skipped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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

}
