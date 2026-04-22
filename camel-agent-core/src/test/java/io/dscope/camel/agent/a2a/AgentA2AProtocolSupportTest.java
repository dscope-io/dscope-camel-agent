package io.dscope.camel.agent.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import io.dscope.camel.agent.runtime.A2ARuntimeProperties;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.a2a.A2AComponentApplicationSupport;
import io.dscope.camel.a2a.config.A2AExchangeProperties;
import io.dscope.camel.a2a.model.Message;
import io.dscope.camel.a2a.model.Part;
import io.dscope.camel.a2a.model.Task;
import io.dscope.camel.a2a.model.dto.SendMessageRequest;
import io.dscope.camel.a2a.model.dto.SendMessageResponse;
import io.dscope.camel.a2a.service.A2APushNotificationConfigService;
import io.dscope.camel.a2a.service.A2ATaskService;
import io.dscope.camel.a2a.service.InMemoryA2ATaskService;
import io.dscope.camel.a2a.service.InMemoryPushNotificationConfigService;
import io.dscope.camel.a2a.service.InMemoryTaskEventService;
import io.dscope.camel.a2a.service.WebhookPushNotificationNotifier;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.main.Main;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentA2AProtocolSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void reusesPreboundSharedTaskInfrastructure() throws Exception {
        Path plansConfig = tempDir.resolve("agents.yaml");
        Files.writeString(plansConfig, """
            plans:
              - name: support
                default: true
                versions:
                  - version: v1
                    default: true
                    blueprint: classpath:agents/support/agent.md
            """);
        Path exposedAgentsConfig = tempDir.resolve("a2a-agents.yaml");
        Files.writeString(exposedAgentsConfig, """
            agents:
              - agentId: support-public
                name: Support Public Agent
                description: Handles support requests
                defaultAgent: true
                version: 1.0.0
                planName: support
                planVersion: v1
            """);

        Main main = new Main();
        InMemoryTaskEventService taskEventService = new InMemoryTaskEventService();
        A2ATaskService taskService = new InMemoryA2ATaskService(taskEventService);
        A2APushNotificationConfigService pushConfigService =
            new InMemoryPushNotificationConfigService(new WebhookPushNotificationNotifier());
        taskEventService.addListener(pushConfigService::onTaskEvent);

        main.bind(A2AComponentApplicationSupport.BEAN_TASK_EVENT_SERVICE, taskEventService);
        main.bind(A2AComponentApplicationSupport.BEAN_TASK_SERVICE, taskService);
        main.bind(A2AComponentApplicationSupport.BEAN_PUSH_CONFIG_SERVICE, pushConfigService);

        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();
        ObjectMapper objectMapper = new ObjectMapper();
        AgentPlanSelectionResolver planSelectionResolver = new AgentPlanSelectionResolver(persistence, objectMapper);

        AgentA2AProtocolSupport.bindIfEnabled(
            main,
            new Properties(),
            new A2ARuntimeProperties(
                true,
                "0.0.0.0",
                8080,
                "http://localhost:8080",
                "/a2a/rpc",
                "/a2a/sse",
                "/.well-known/agent-card.json",
                "direct:agent",
                exposedAgentsConfig.toString(),
                plansConfig.toString(),
                "classpath:agents/support/agent.md"
            ),
            persistence,
            planSelectionResolver,
            objectMapper
        );

        assertSame(taskEventService, main.lookup(A2AComponentApplicationSupport.BEAN_TASK_EVENT_SERVICE, InMemoryTaskEventService.class));
        assertSame(taskService, main.lookup(A2AComponentApplicationSupport.BEAN_TASK_SERVICE, A2ATaskService.class));
        assertSame(pushConfigService, main.lookup(A2AComponentApplicationSupport.BEAN_PUSH_CONFIG_SERVICE, A2APushNotificationConfigService.class));

        SendMessageRequest request = new SendMessageRequest();
        request.setMessage(message("user", "Check shared task"));
        Task created = taskService.sendMessage(request);

        A2ATaskService boundTaskService = main.lookup(A2AComponentApplicationSupport.BEAN_TASK_SERVICE, A2ATaskService.class);
        assertEquals(created.getTaskId(), boundTaskService.getTask(created.getTaskId()).getTaskId());
    }

    @Test
    void preservesRootConversationIdAcrossInboundMultiHopChain() throws Exception {
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();
        ObjectMapper objectMapper = new ObjectMapper();
        InMemoryTaskEventService taskEventService = new InMemoryTaskEventService();
        AgentA2ATaskAdapter taskAdapter = new AgentA2ATaskAdapter(new InMemoryA2ATaskService(taskEventService), persistence, objectMapper);
        A2AExposedAgentSpec spec = new A2AExposedAgentSpec();
        spec.setAgentId("support-public");
        spec.setName("Support Public Agent");
        spec.setDescription("Handles support requests");
        spec.setVersion("1.0.0");
        spec.setDefaultAgent(true);
        spec.setPlanName("support");
        spec.setPlanVersion("v1");
        A2AExposedAgentCatalog catalog = new A2AExposedAgentCatalog(List.of(spec));
        Processor processor = sendMessageProcessor("direct:agent", catalog, taskAdapter, objectMapper, persistence);

        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:agent").setBody(simple("handled ${body}"));
                }
            });
            camelContext.start();

            SendMessageResponse hopB = invokeSendMessage(
                processor,
                camelContext,
                objectMapper,
                "conv-b",
                Map.of(
                    "agentId", "support-public",
                    "linkedConversationId", "conv-b",
                    "parentConversationId", "conv-a",
                    "rootConversationId", "conv-a"
                )
            );
            SendMessageResponse hopC = invokeSendMessage(
                processor,
                camelContext,
                objectMapper,
                "conv-c",
                Map.of(
                    "agentId", "support-public",
                    "linkedConversationId", "conv-c",
                    "parentConversationId", "conv-b",
                    "rootConversationId", "conv-a"
                )
            );

            Map<String, Object> hopBMetadata = hopB.getTask().getMetadata();
            Map<String, Object> hopCMetadata = hopC.getTask().getMetadata();
            assertEquals("conv-a", metadataValue(hopBMetadata, "rootConversationId"));
            assertEquals("conv-a", metadataValue(hopCMetadata, "rootConversationId"));
            assertEquals("conv-a", CorrelationRegistry.global().resolve("conv-b", "a2a.rootConversationId", ""));
            assertEquals("conv-b", CorrelationRegistry.global().resolve("conv-c", "a2a.parentConversationId", ""));
            assertEquals("conv-a", CorrelationRegistry.global().resolve("conv-c", "a2a.rootConversationId", ""));
        } finally {
            CorrelationRegistry.global().clear("conv-b");
            CorrelationRegistry.global().clear("conv-c");
        }
    }

    @SuppressWarnings("unchecked")
    private static Processor sendMessageProcessor(String agentEndpointUri,
                                                  A2AExposedAgentCatalog catalog,
                                                  AgentA2ATaskAdapter taskAdapter,
                                                  ObjectMapper objectMapper,
                                                  InMemoryPersistenceFacade persistence) throws Exception {
        Class<?> processorClass = Class.forName("io.dscope.camel.agent.a2a.AgentA2AProtocolSupport$SendMessageProcessor");
        Constructor<?> constructor = processorClass.getDeclaredConstructor(
            String.class,
            A2AExposedAgentCatalog.class,
            AgentA2ATaskAdapter.class,
            ObjectMapper.class,
            A2AParentConversationNotifier.class
        );
        constructor.setAccessible(true);
        return (Processor) constructor.newInstance(
            agentEndpointUri,
            catalog,
            taskAdapter,
            objectMapper,
            new A2AParentConversationNotifier(persistence, objectMapper)
        );
    }

    private static SendMessageResponse invokeSendMessage(Processor processor,
                                                         DefaultCamelContext camelContext,
                                                         ObjectMapper objectMapper,
                                                         String linkedConversationId,
                                                         Map<String, Object> metadata) throws Exception {
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId("remote-" + linkedConversationId);
        request.setMessage(message("user", "hop " + linkedConversationId));
        request.setMetadata(new LinkedHashMap<>(metadata));

        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(A2AExchangeProperties.NORMALIZED_PARAMS, objectMapper.valueToTree(request));
        processor.process(exchange);
        Object result = exchange.getProperty(A2AExchangeProperties.METHOD_RESULT);
        return assertInstanceOf(SendMessageResponse.class, result);
    }

    @SuppressWarnings("unchecked")
    private static String metadataValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value != null) {
            return String.valueOf(value);
        }
        Object camelAgent = metadata.get("camelAgent");
        if (camelAgent instanceof Map<?, ?> camelAgentMap) {
            Object nested = ((Map<String, Object>) camelAgentMap).get(key);
            return nested == null ? "" : String.valueOf(nested);
        }
        return "";
    }

    private static Message message(String role, String text) {
        Part part = new Part();
        part.setType("text");
        part.setMimeType("text/plain");
        part.setText(text);

        Message message = new Message();
        message.setRole(role);
        message.setParts(List.of(part));
        message.setMessageId(role + "-message-" + text.hashCode());
        return message;
    }
}
