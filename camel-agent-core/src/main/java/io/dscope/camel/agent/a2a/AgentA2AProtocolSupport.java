package io.dscope.camel.agent.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.config.CorrelationKeys;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import io.dscope.camel.agent.runtime.A2ARuntimeProperties;
import io.dscope.camel.agent.runtime.AgentPlanCatalog;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.a2a.A2AComponentApplicationSupport;
import io.dscope.camel.a2a.catalog.AgentCardCatalog;
import io.dscope.camel.a2a.config.A2AExchangeProperties;
import io.dscope.camel.a2a.config.A2AProtocolMethods;
import io.dscope.camel.a2a.model.Message;
import io.dscope.camel.a2a.model.Part;
import io.dscope.camel.a2a.model.Task;
import io.dscope.camel.a2a.model.TaskSubscription;
import io.dscope.camel.a2a.model.dto.CancelTaskRequest;
import io.dscope.camel.a2a.model.dto.CancelTaskResponse;
import io.dscope.camel.a2a.model.dto.GetTaskRequest;
import io.dscope.camel.a2a.model.dto.GetTaskResponse;
import io.dscope.camel.a2a.model.dto.ListTasksRequest;
import io.dscope.camel.a2a.model.dto.ListTasksResponse;
import io.dscope.camel.a2a.model.dto.SendMessageRequest;
import io.dscope.camel.a2a.model.dto.SendMessageResponse;
import io.dscope.camel.a2a.model.dto.SendStreamingMessageRequest;
import io.dscope.camel.a2a.model.dto.SendStreamingMessageResponse;
import io.dscope.camel.a2a.model.dto.SubscribeToTaskRequest;
import io.dscope.camel.a2a.model.dto.SubscribeToTaskResponse;
import io.dscope.camel.a2a.processor.A2AErrorProcessor;
import io.dscope.camel.a2a.processor.A2AInvalidParamsException;
import io.dscope.camel.a2a.processor.A2AJsonRpcEnvelopeProcessor;
import io.dscope.camel.a2a.processor.A2AMethodDispatchProcessor;
import io.dscope.camel.a2a.processor.AgentCardDiscoveryProcessor;
import io.dscope.camel.a2a.processor.CreatePushNotificationConfigProcessor;
import io.dscope.camel.a2a.processor.DeletePushNotificationConfigProcessor;
import io.dscope.camel.a2a.processor.GetExtendedAgentCardProcessor;
import io.dscope.camel.a2a.processor.GetPushNotificationConfigProcessor;
import io.dscope.camel.a2a.processor.ListPushNotificationConfigsProcessor;
import io.dscope.camel.a2a.processor.A2ATaskSseProcessor;
import io.dscope.camel.a2a.service.A2APushNotificationConfigService;
import io.dscope.camel.a2a.service.InMemoryPushNotificationConfigService;
import io.dscope.camel.a2a.service.InMemoryTaskEventService;
import io.dscope.camel.a2a.service.WebhookPushNotificationNotifier;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.main.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentA2AProtocolSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentA2AProtocolSupport.class);

    private AgentA2AProtocolSupport() {
    }

    public static void bindIfEnabled(Main main,
                                     A2ARuntimeProperties runtimeProperties,
                                     PersistenceFacade persistenceFacade,
                                     AgentPlanSelectionResolver planSelectionResolver,
                                     ObjectMapper objectMapper) {
        if (runtimeProperties == null || !runtimeProperties.enabled()) {
            return;
        }
        A2AExposedAgentCatalog exposedAgentCatalog =
            new A2AExposedAgentCatalogLoader().load(runtimeProperties.exposedAgentsConfig());
        validatePlanMappings(planSelectionResolver, runtimeProperties, exposedAgentCatalog);

        InMemoryTaskEventService taskEventService = new InMemoryTaskEventService();
        AgentA2ATaskRepository taskRepository = new AgentA2ATaskRepository(persistenceFacade, objectMapper, taskEventService);
        A2APushNotificationConfigService pushConfigService =
            new InMemoryPushNotificationConfigService(new WebhookPushNotificationNotifier());
        taskEventService.addListener(pushConfigService::onTaskEvent);

        AgentCardCatalog agentCardCatalog =
            new AgentA2AAgentCardCatalog(exposedAgentCatalog, runtimeProperties.rpcEndpointUrl());

        Processor sendMessageProcessor = new SendMessageProcessor(
            runtimeProperties.agentEndpointUri(),
            exposedAgentCatalog,
            taskRepository,
            objectMapper
        );
        Processor sendStreamingMessageProcessor = new SendStreamingMessageProcessor(
            sendMessageProcessor,
            taskRepository,
            taskEventService,
            objectMapper,
            runtimeProperties
        );
        Processor getTaskProcessor = exchange -> {
            GetTaskRequest request = objectMapper.convertValue(requiredParams(exchange, "GetTask requires params object"), GetTaskRequest.class);
            if (request.getTaskId() == null || request.getTaskId().isBlank()) {
                throw new A2AInvalidParamsException("GetTask requires taskId");
            }
            Task task = taskRepository.getTask(request.getTaskId());
            GetTaskResponse response = new GetTaskResponse();
            response.setTask(task);
            exchange.setProperty(A2AExchangeProperties.METHOD_RESULT, response);
        };
        Processor listTasksProcessor = exchange -> {
            Object params = exchange.getProperty(A2AExchangeProperties.NORMALIZED_PARAMS);
            ListTasksRequest request = params == null ? new ListTasksRequest() : objectMapper.convertValue(params, ListTasksRequest.class);
            if (request.getLimit() != null && request.getLimit() <= 0) {
                throw new A2AInvalidParamsException("ListTasks limit must be greater than zero");
            }
            ListTasksResponse response = new ListTasksResponse();
            response.setTasks(taskRepository.listTasks(request.getState(), request.getLimit()));
            response.setNextCursor(null);
            exchange.setProperty(A2AExchangeProperties.METHOD_RESULT, response);
        };
        Processor cancelTaskProcessor = exchange -> {
            CancelTaskRequest request = objectMapper.convertValue(requiredParams(exchange, "CancelTask requires params object"), CancelTaskRequest.class);
            if (request.getTaskId() == null || request.getTaskId().isBlank()) {
                throw new A2AInvalidParamsException("CancelTask requires taskId");
            }
            Task task = taskRepository.cancelTask(request.getTaskId(), request.getReason());
            taskRepository.appendConversationEvent(
                conversationId(task),
                task.getTaskId(),
                "conversation.a2a.task.canceled",
                Map.of(
                    "taskId", task.getTaskId(),
                    "reason", request.getReason() == null ? "" : request.getReason()
                )
            );
            CancelTaskResponse response = new CancelTaskResponse();
            response.setTask(task);
            response.setCanceled(true);
            exchange.setProperty(A2AExchangeProperties.METHOD_RESULT, response);
        };
        Processor subscribeToTaskProcessor = exchange -> {
            SubscribeToTaskRequest request = objectMapper.convertValue(requiredParams(exchange, "SubscribeToTask requires params object"),
                SubscribeToTaskRequest.class);
            if (request.getTaskId() == null || request.getTaskId().isBlank()) {
                throw new A2AInvalidParamsException("SubscribeToTask requires taskId");
            }
            taskRepository.getTask(request.getTaskId());
            long afterSequence = request.getAfterSequence() == null ? 0L : Math.max(0L, request.getAfterSequence());
            TaskSubscription subscription = taskEventService.createSubscription(request.getTaskId(), afterSequence);
            SubscribeToTaskResponse response = new SubscribeToTaskResponse();
            response.setSubscriptionId(subscription.getSubscriptionId());
            response.setTaskId(request.getTaskId());
            response.setAfterSequence(afterSequence);
            response.setTerminal(taskEventService.isTaskTerminal(request.getTaskId()));
            response.setStreamUrl(buildStreamUrl(runtimeProperties, request.getTaskId(), subscription.getSubscriptionId(), afterSequence, request.getLimit()));
            exchange.setProperty(A2AExchangeProperties.METHOD_RESULT, response);
        };
        Processor createPushConfigProcessor = new CreatePushNotificationConfigProcessor(pushConfigService);
        Processor getPushConfigProcessor = new GetPushNotificationConfigProcessor(pushConfigService);
        Processor listPushConfigsProcessor = new ListPushNotificationConfigsProcessor(pushConfigService);
        Processor deletePushConfigProcessor = new DeletePushNotificationConfigProcessor(pushConfigService);
        Processor getExtendedAgentCardProcessor = new GetExtendedAgentCardProcessor(agentCardCatalog);

        Map<String, Processor> methods = Map.ofEntries(
            Map.entry(A2AProtocolMethods.SEND_MESSAGE, sendMessageProcessor),
            Map.entry(A2AProtocolMethods.SEND_STREAMING_MESSAGE, sendStreamingMessageProcessor),
            Map.entry(A2AProtocolMethods.GET_TASK, getTaskProcessor),
            Map.entry(A2AProtocolMethods.LIST_TASKS, listTasksProcessor),
            Map.entry(A2AProtocolMethods.CANCEL_TASK, cancelTaskProcessor),
            Map.entry(A2AProtocolMethods.SUBSCRIBE_TO_TASK, subscribeToTaskProcessor),
            Map.entry(A2AProtocolMethods.CREATE_PUSH_NOTIFICATION_CONFIG, createPushConfigProcessor),
            Map.entry(A2AProtocolMethods.GET_PUSH_NOTIFICATION_CONFIG, getPushConfigProcessor),
            Map.entry(A2AProtocolMethods.LIST_PUSH_NOTIFICATION_CONFIGS, listPushConfigsProcessor),
            Map.entry(A2AProtocolMethods.DELETE_PUSH_NOTIFICATION_CONFIG, deletePushConfigProcessor),
            Map.entry(A2AProtocolMethods.GET_EXTENDED_AGENT_CARD, getExtendedAgentCardProcessor)
        );

        bind(main, A2AComponentApplicationSupport.BEAN_TASK_EVENT_SERVICE, taskEventService);
        bind(main, A2AComponentApplicationSupport.BEAN_PUSH_CONFIG_SERVICE, pushConfigService);
        bind(main, A2AComponentApplicationSupport.BEAN_TASK_SERVICE, taskRepository);
        bind(main, A2AComponentApplicationSupport.BEAN_AGENT_CARD_CATALOG, agentCardCatalog);
        bind(main, A2AComponentApplicationSupport.BEAN_CREATE_PUSH_CONFIG_PROCESSOR, createPushConfigProcessor);
        bind(main, A2AComponentApplicationSupport.BEAN_GET_PUSH_CONFIG_PROCESSOR, getPushConfigProcessor);
        bind(main, A2AComponentApplicationSupport.BEAN_LIST_PUSH_CONFIGS_PROCESSOR, listPushConfigsProcessor);
        bind(main, A2AComponentApplicationSupport.BEAN_DELETE_PUSH_CONFIG_PROCESSOR, deletePushConfigProcessor);
        bind(main, A2AComponentApplicationSupport.BEAN_ENVELOPE_PROCESSOR, new A2AJsonRpcEnvelopeProcessor(A2AProtocolMethods.CORE_METHODS));
        bind(main, A2AComponentApplicationSupport.BEAN_ERROR_PROCESSOR, new A2AErrorProcessor());
        bind(main, A2AComponentApplicationSupport.BEAN_METHOD_PROCESSOR, new A2AMethodDispatchProcessor(methods));
        bind(main, A2AComponentApplicationSupport.BEAN_SSE_PROCESSOR, new A2ATaskSseProcessor(taskEventService));
        bind(main, A2AComponentApplicationSupport.BEAN_AGENT_CARD_DISCOVERY_PROCESSOR, new AgentCardDiscoveryProcessor(agentCardCatalog));
        bind(main, A2AComponentApplicationSupport.BEAN_GET_EXTENDED_AGENT_CARD_PROCESSOR, getExtendedAgentCardProcessor);

        LOGGER.info("Agent runtime A2A support bound: agents={}, rpc={}, agentCard={}",
            exposedAgentCatalog.agents().size(),
            runtimeProperties.rpcEndpointUrl(),
            runtimeProperties.publicBaseUrl() + runtimeProperties.agentCardPath());
    }

    private static void validatePlanMappings(AgentPlanSelectionResolver planSelectionResolver,
                                             A2ARuntimeProperties runtimeProperties,
                                             A2AExposedAgentCatalog exposedAgentCatalog) {
        if (planSelectionResolver == null) {
            throw new IllegalArgumentException("A2A runtime requires AgentPlanSelectionResolver");
        }
        if (runtimeProperties.plansConfig() == null || runtimeProperties.plansConfig().isBlank()) {
            throw new IllegalArgumentException("A2A runtime requires agent.agents-config for exposed plan mappings");
        }
        AgentPlanCatalog catalog = planSelectionResolver.loadCatalog(runtimeProperties.plansConfig());
        for (A2AExposedAgentSpec spec : exposedAgentCatalog.agents()) {
            catalog.requireVersion(spec.getPlanName(), spec.getPlanVersion());
        }
    }

    private static void bind(Main main, String beanName, Object bean) {
        if (main.lookup(beanName, Object.class) == null) {
            main.bind(beanName, bean);
        }
    }

    private static Object requiredParams(Exchange exchange, String message) {
        Object params = exchange.getProperty(A2AExchangeProperties.NORMALIZED_PARAMS);
        if (params == null) {
            throw new A2AInvalidParamsException(message);
        }
        return params;
    }

    private static String buildStreamUrl(A2ARuntimeProperties runtimeProperties,
                                         String taskId,
                                         String subscriptionId,
                                         long afterSequence,
                                         Integer limit) {
        String suffix = limit == null ? "" : "&limit=" + limit;
        return runtimeProperties.sseBaseUrl()
            + "/"
            + taskId
            + "?subscriptionId="
            + subscriptionId
            + "&afterSequence="
            + afterSequence
            + suffix;
    }

    private static String conversationId(Task task) {
        if (task == null || task.getMetadata() == null) {
            return "";
        }
        Object camelAgent = task.getMetadata().get("camelAgent");
        if (camelAgent instanceof Map<?, ?> map) {
            Object conversationId = map.get("localConversationId");
            return conversationId == null ? "" : String.valueOf(conversationId);
        }
        return "";
    }

    private static final class SendMessageProcessor implements Processor {

        private final String agentEndpointUri;
        private final A2AExposedAgentCatalog exposedAgentCatalog;
        private final AgentA2ATaskRepository taskRepository;
        private final ObjectMapper objectMapper;

        private SendMessageProcessor(String agentEndpointUri,
                                     A2AExposedAgentCatalog exposedAgentCatalog,
                                     AgentA2ATaskRepository taskRepository,
                                     ObjectMapper objectMapper) {
            this.agentEndpointUri = agentEndpointUri;
            this.exposedAgentCatalog = exposedAgentCatalog;
            this.taskRepository = taskRepository;
            this.objectMapper = objectMapper;
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            SendMessageRequest request = objectMapper.convertValue(requiredParams(exchange, "SendMessage requires params object"), SendMessageRequest.class);
            if (request.getMessage() == null) {
                throw new A2AInvalidParamsException("SendMessage requires message");
            }
            String idempotencyKey = normalizeIdempotencyKey(request);
            Task existing = taskRepository.existingByIdempotency(idempotencyKey);
            if (existing != null) {
                SendMessageResponse response = new SendMessageResponse();
                response.setTask(existing);
                exchange.setProperty(A2AExchangeProperties.METHOD_RESULT, response);
                return;
            }

            A2AExposedAgentSpec exposedAgent = resolveExposedAgent(request);
            String localConversationId = firstNonBlank(
                metadataValue(request.getMetadata(), "linkedConversationId"),
                metadataValue(request.getMetadata(), "camelAgent.linkedConversationId"),
                UUID.randomUUID().toString()
            );
            String parentConversationId = firstNonBlank(
                metadataValue(request.getMetadata(), "parentConversationId"),
                metadataValue(request.getMetadata(), "camelAgent.parentConversationId")
            );
            String rootConversationId = firstNonBlank(
                metadataValue(request.getMetadata(), "rootConversationId"),
                metadataValue(request.getMetadata(), "camelAgent.rootConversationId"),
                parentConversationId,
                localConversationId
            );
            String remoteConversationId = firstNonBlank(request.getConversationId(), metadataValue(request.getMetadata(), "remoteConversationId"));
            String userText = extractMessageText(request.getMessage());
            String taskId = UUID.randomUUID().toString();

            bindCorrelation(localConversationId, exposedAgent.getAgentId(), remoteConversationId, taskId, parentConversationId, rootConversationId);

            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put(AgentHeaders.CONVERSATION_ID, localConversationId);
            headers.put(AgentHeaders.PLAN_NAME, exposedAgent.getPlanName());
            headers.put(AgentHeaders.PLAN_VERSION, exposedAgent.getPlanVersion());
            headers.put(AgentHeaders.A2A_AGENT_ID, exposedAgent.getAgentId());
            headers.put(AgentHeaders.A2A_REMOTE_CONVERSATION_ID, remoteConversationId);
            headers.put(AgentHeaders.A2A_REMOTE_TASK_ID, taskId);
            headers.put(AgentHeaders.A2A_LINKED_CONVERSATION_ID, localConversationId);
            headers.put(AgentHeaders.A2A_PARENT_CONVERSATION_ID, parentConversationId);
            headers.put(AgentHeaders.A2A_ROOT_CONVERSATION_ID, rootConversationId);

            taskRepository.appendConversationEvent(
                localConversationId,
                taskId,
                "conversation.a2a.request.accepted",
                Map.of(
                    "agentId", exposedAgent.getAgentId(),
                    "planName", exposedAgent.getPlanName(),
                    "planVersion", exposedAgent.getPlanVersion(),
                    "remoteConversationId", remoteConversationId == null ? "" : remoteConversationId,
                    "parentConversationId", parentConversationId,
                    "rootConversationId", rootConversationId,
                    "message", userText
                )
            );

            String agentReply;
            ProducerTemplate producerTemplate = exchange.getContext().createProducerTemplate();
            try {
                Object response = producerTemplate.requestBodyAndHeaders(agentEndpointUri, userText, headers);
                agentReply = response == null ? "" : String.valueOf(response);
            } finally {
                producerTemplate.stop();
            }

            Message assistantMessage = assistantMessage(request.getMessage(), agentReply);
            Task task = taskRepository.createCompletedTask(
                taskId,
                idempotencyKey,
                localConversationId,
                request.getMessage(),
                assistantMessage,
                taskMetadata(exposedAgent, localConversationId, remoteConversationId, taskId, parentConversationId, rootConversationId, request.getMetadata())
            );

            taskRepository.appendConversationEvent(
                localConversationId,
                taskId,
                "conversation.a2a.response.completed",
                Map.of(
                    "agentId", exposedAgent.getAgentId(),
                    "planName", exposedAgent.getPlanName(),
                    "planVersion", exposedAgent.getPlanVersion(),
                    "responseText", agentReply,
                    "linkedConversationId", localConversationId
                )
            );

            SendMessageResponse response = new SendMessageResponse();
            response.setTask(task);
            exchange.setProperty(A2AExchangeProperties.METHOD_RESULT, response);
        }

        private A2AExposedAgentSpec resolveExposedAgent(SendMessageRequest request) {
            String requestedAgentId = firstNonBlank(
                metadataValue(request.getMetadata(), "agentId"),
                metadataValue(request.getMetadata(), "a2aAgentId"),
                metadataValue(request.getMetadata(), "camelAgent.agentId"),
                request.getMessage() == null ? "" : metadataValue(request.getMessage().getMetadata(), "agentId"),
                request.getMessage() == null ? "" : metadataValue(request.getMessage().getMetadata(), "camelAgent.agentId")
            );
            return exposedAgentCatalog.requireAgent(requestedAgentId);
        }

        private void bindCorrelation(String conversationId,
                                     String agentId,
                                     String remoteConversationId,
                                     String remoteTaskId,
                                     String parentConversationId,
                                     String rootConversationId) {
            CorrelationRegistry registry = CorrelationRegistry.global();
            registry.bind(conversationId, CorrelationKeys.A2A_AGENT_ID, agentId);
            registry.bind(conversationId, CorrelationKeys.A2A_LINKED_CONVERSATION_ID, conversationId);
            if (remoteConversationId != null && !remoteConversationId.isBlank()) {
                registry.bind(conversationId, CorrelationKeys.A2A_REMOTE_CONVERSATION_ID, remoteConversationId);
            }
            if (remoteTaskId != null && !remoteTaskId.isBlank()) {
                registry.bind(conversationId, CorrelationKeys.A2A_REMOTE_TASK_ID, remoteTaskId);
            }
            if (parentConversationId != null && !parentConversationId.isBlank()) {
                registry.bind(conversationId, CorrelationKeys.A2A_PARENT_CONVERSATION_ID, parentConversationId);
            }
            if (rootConversationId != null && !rootConversationId.isBlank()) {
                registry.bind(conversationId, CorrelationKeys.A2A_ROOT_CONVERSATION_ID, rootConversationId);
            }
        }

        private Map<String, Object> taskMetadata(A2AExposedAgentSpec exposedAgent,
                                                 String localConversationId,
                                                 String remoteConversationId,
                                                 String taskId,
                                                 String parentConversationId,
                                                 String rootConversationId,
                                                 Map<String, Object> requestMetadata) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (requestMetadata != null && !requestMetadata.isEmpty()) {
                metadata.putAll(requestMetadata);
            }
            Map<String, Object> camelAgent = new LinkedHashMap<>();
            camelAgent.put("agentId", exposedAgent.getAgentId());
            camelAgent.put("planName", exposedAgent.getPlanName());
            camelAgent.put("planVersion", exposedAgent.getPlanVersion());
            camelAgent.put("localConversationId", localConversationId);
            camelAgent.put("linkedConversationId", localConversationId);
            camelAgent.put("remoteConversationId", remoteConversationId == null ? "" : remoteConversationId);
            camelAgent.put("remoteTaskId", taskId);
            camelAgent.put("parentConversationId", parentConversationId == null ? "" : parentConversationId);
            camelAgent.put("rootConversationId", rootConversationId == null ? "" : rootConversationId);
            camelAgent.put("selectedAt", Instant.now().toString());
            metadata.put("camelAgent", camelAgent);
            metadata.put("agentId", exposedAgent.getAgentId());
            metadata.put("planName", exposedAgent.getPlanName());
            metadata.put("planVersion", exposedAgent.getPlanVersion());
            metadata.put("linkedConversationId", localConversationId);
            metadata.put("remoteConversationId", remoteConversationId == null ? "" : remoteConversationId);
            metadata.put("remoteTaskId", taskId);
            return metadata;
        }

        private Message assistantMessage(Message requestMessage, String replyText) {
            Message response = new Message();
            response.setMessageId(UUID.randomUUID().toString());
            response.setRole("assistant");
            response.setInReplyTo(requestMessage == null ? null : requestMessage.getMessageId());
            response.setCreatedAt(Instant.now().toString());
            Part part = new Part();
            part.setPartId(UUID.randomUUID().toString());
            part.setType("text");
            part.setMimeType("text/plain");
            part.setText(replyText == null ? "" : replyText);
            response.setParts(List.of(part));
            return response;
        }

        private String extractMessageText(Message message) {
            if (message == null || message.getParts() == null) {
                return "";
            }
            return message.getParts().stream()
                .filter(part -> part != null && part.getText() != null && !part.getText().isBlank())
                .map(Part::getText)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        }

        private String normalizeIdempotencyKey(SendMessageRequest request) {
            if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
                return request.getIdempotencyKey().trim();
            }
            if (request.getMessage() != null && request.getMessage().getMessageId() != null && !request.getMessage().getMessageId().isBlank()) {
                return request.getMessage().getMessageId().trim();
            }
            return "";
        }

        private String metadataValue(Map<String, Object> metadata, String key) {
            if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
                return "";
            }
            try {
                JsonNode root = objectMapper.valueToTree(metadata);
                JsonNode current = root;
                for (String part : key.split("\\.")) {
                    current = current.path(part);
                }
                if (current.isMissingNode() || current.isNull()) {
                    return "";
                }
                return current.asText("");
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    private static final class SendStreamingMessageProcessor implements Processor {

        private final Processor sendMessageProcessor;
        private final AgentA2ATaskRepository taskRepository;
        private final InMemoryTaskEventService taskEventService;
        private final ObjectMapper objectMapper;
        private final A2ARuntimeProperties runtimeProperties;

        private SendStreamingMessageProcessor(Processor sendMessageProcessor,
                                              AgentA2ATaskRepository taskRepository,
                                              InMemoryTaskEventService taskEventService,
                                              ObjectMapper objectMapper,
                                              A2ARuntimeProperties runtimeProperties) {
            this.sendMessageProcessor = sendMessageProcessor;
            this.taskRepository = taskRepository;
            this.taskEventService = taskEventService;
            this.objectMapper = objectMapper;
            this.runtimeProperties = runtimeProperties;
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            SendStreamingMessageRequest request = objectMapper.convertValue(requiredParams(exchange, "SendStreamingMessage requires params object"),
                SendStreamingMessageRequest.class);
            if (request.getMessage() == null) {
                throw new A2AInvalidParamsException("SendStreamingMessage requires message");
            }
            SendMessageRequest adapted = new SendMessageRequest();
            adapted.setMessage(request.getMessage());
            adapted.setConversationId(request.getConversationId());
            adapted.setIdempotencyKey(request.getIdempotencyKey());
            adapted.setMetadata(request.getMetadata());
            exchange.setProperty(A2AExchangeProperties.NORMALIZED_PARAMS, objectMapper.valueToTree(adapted));
            sendMessageProcessor.process(exchange);
            SendMessageResponse sendMessageResponse = objectMapper.convertValue(
                exchange.getProperty(A2AExchangeProperties.METHOD_RESULT),
                SendMessageResponse.class
            );
            Task task = sendMessageResponse.getTask();
            TaskSubscription subscription = taskEventService.createSubscription(task.getTaskId(), 0L);
            SendStreamingMessageResponse response = new SendStreamingMessageResponse();
            response.setTask(taskRepository.getTask(task.getTaskId()));
            response.setSubscriptionId(subscription.getSubscriptionId());
            response.setStreamUrl(buildStreamUrl(runtimeProperties, task.getTaskId(), subscription.getSubscriptionId(), 0L, null));
            exchange.setProperty(A2AExchangeProperties.METHOD_RESULT, response);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
