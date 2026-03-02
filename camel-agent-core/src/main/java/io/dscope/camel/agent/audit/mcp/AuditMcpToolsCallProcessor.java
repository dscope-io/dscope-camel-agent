package io.dscope.camel.agent.audit.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.audit.AuditAgentBlueprintProcessor;
import io.dscope.camel.agent.audit.AuditConversationAgentMessageProcessor;
import io.dscope.camel.agent.audit.AuditConversationListProcessor;
import io.dscope.camel.agent.audit.AuditConversationSessionDataProcessor;
import io.dscope.camel.agent.audit.AuditConversationViewProcessor;
import io.dscope.camel.agent.audit.AuditTrailSearchProcessor;
import io.dscope.camel.agent.runtime.RuntimeAuditGranularityProcessor;
import io.dscope.camel.agent.runtime.RuntimeConversationCloseProcessor;
import io.dscope.camel.agent.runtime.RuntimeConversationPersistenceProcessor;
import io.dscope.camel.agent.runtime.RuntimePurgePreviewProcessor;
import io.dscope.camel.agent.runtime.RuntimeResourceRefreshProcessor;
import io.dscope.camel.mcp.processor.AbstractMcpResponseProcessor;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.support.DefaultExchange;

public class AuditMcpToolsCallProcessor extends AbstractMcpResponseProcessor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final AuditTrailSearchProcessor auditTrailSearchProcessor;
    private final AuditConversationListProcessor auditConversationListProcessor;
    private final AuditConversationViewProcessor auditConversationViewProcessor;
    private final AuditConversationSessionDataProcessor auditConversationSessionDataProcessor;
    private final AuditConversationAgentMessageProcessor auditConversationAgentMessageProcessor;
    private final AuditAgentBlueprintProcessor auditAgentBlueprintProcessor;
    private final RuntimeAuditGranularityProcessor runtimeAuditGranularityProcessor;
    private final RuntimeResourceRefreshProcessor runtimeResourceRefreshProcessor;
    private final RuntimeConversationPersistenceProcessor runtimeConversationPersistenceProcessor;
    private final RuntimeConversationCloseProcessor runtimeConversationCloseProcessor;
    private final RuntimePurgePreviewProcessor runtimePurgePreviewProcessor;

    public AuditMcpToolsCallProcessor(ObjectMapper objectMapper,
                                      AuditTrailSearchProcessor auditTrailSearchProcessor,
                                      AuditConversationListProcessor auditConversationListProcessor,
                                      AuditConversationViewProcessor auditConversationViewProcessor,
                                      AuditConversationSessionDataProcessor auditConversationSessionDataProcessor,
                                      AuditConversationAgentMessageProcessor auditConversationAgentMessageProcessor,
                                      AuditAgentBlueprintProcessor auditAgentBlueprintProcessor,
                                      RuntimeAuditGranularityProcessor runtimeAuditGranularityProcessor,
                                      RuntimeResourceRefreshProcessor runtimeResourceRefreshProcessor,
                                      RuntimeConversationPersistenceProcessor runtimeConversationPersistenceProcessor,
                                      RuntimeConversationCloseProcessor runtimeConversationCloseProcessor,
                                      RuntimePurgePreviewProcessor runtimePurgePreviewProcessor) {
        this.objectMapper = objectMapper;
        this.auditTrailSearchProcessor = auditTrailSearchProcessor;
        this.auditConversationListProcessor = auditConversationListProcessor;
        this.auditConversationViewProcessor = auditConversationViewProcessor;
        this.auditConversationSessionDataProcessor = auditConversationSessionDataProcessor;
        this.auditConversationAgentMessageProcessor = auditConversationAgentMessageProcessor;
        this.auditAgentBlueprintProcessor = auditAgentBlueprintProcessor;
        this.runtimeAuditGranularityProcessor = runtimeAuditGranularityProcessor;
        this.runtimeResourceRefreshProcessor = runtimeResourceRefreshProcessor;
        this.runtimeConversationPersistenceProcessor = runtimeConversationPersistenceProcessor;
        this.runtimeConversationCloseProcessor = runtimeConversationCloseProcessor;
        this.runtimePurgePreviewProcessor = runtimePurgePreviewProcessor;
    }

    @Override
    protected void handleResponse(Exchange exchange) throws Exception {
        String toolName = getToolName(exchange);
        if (toolName == null || toolName.isBlank()) {
            writeError(exchange, error(-32602, "Tool name is required for tools/call"), 400);
            return;
        }

        Map<String, Object> arguments = getRequestParameters(exchange);
        final Map<String, Object> effectiveArguments = arguments == null ? Map.of() : arguments;

        switch (toolName) {
            case "audit.events.search" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, auditTrailSearchProcessor,
                message -> setHeaders(message, effectiveArguments, "conversationId", "type", "q", "from", "to", "limit")));
            case "audit.conversations.list" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, auditConversationListProcessor,
                message -> setHeaders(message, effectiveArguments, "q", "topic", "sortBy", "order", "limit")));
            case "audit.conversation.view" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, auditConversationViewProcessor,
                message -> setHeaders(message, effectiveArguments, "conversationId", "limit")));
            case "audit.conversation.sessionData" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, auditConversationSessionDataProcessor,
                message -> setHeaders(message, effectiveArguments, "conversationId", "sessionId", "type", "q", "from", "to", "limit")));
            case "audit.conversation.agentMessage" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, auditConversationAgentMessageProcessor,
                message -> {
                    setHeaders(message, effectiveArguments, "conversationId", "message", "text", "sessionId", "runId", "threadId");
                    message.setHeader(Exchange.CONTENT_TYPE, "application/json");
                    message.setBody(objectMapper.writeValueAsString(effectiveArguments));
                }));
            case "audit.agent.blueprint" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, auditAgentBlueprintProcessor,
                message -> setHeaders(message, effectiveArguments, "conversationId")));
            case "runtime.audit.granularity.get" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, runtimeAuditGranularityProcessor,
                message -> {
                }));
            case "runtime.audit.granularity.set" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, runtimeAuditGranularityProcessor,
                message -> setHeaders(message, effectiveArguments, "granularity", "auditGranularity")));
            case "runtime.conversation.persistence.get" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, runtimeConversationPersistenceProcessor,
                message -> {
                }));
            case "runtime.conversation.persistence.set" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, runtimeConversationPersistenceProcessor,
                message -> setHeaders(message, effectiveArguments, "enabled", "conversationPersistenceEnabled")));
            case "runtime.refresh" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, runtimeResourceRefreshProcessor,
                message -> {
                    setHeaders(message, effectiveArguments, "conversationId");
                    message.setHeader(Exchange.CONTENT_TYPE, "application/json");
                    message.setBody(objectMapper.writeValueAsString(effectiveArguments));
                }));
            case "runtime.conversation.close" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, runtimeConversationCloseProcessor,
                message -> {
                    setHeaders(message, effectiveArguments, "conversationId");
                    message.setHeader(Exchange.CONTENT_TYPE, "application/json");
                    message.setBody(objectMapper.writeValueAsString(effectiveArguments));
                }));
            case "runtime.purge.preview" -> writeResult(exchange, invokeTool(exchange, toolName, effectiveArguments, runtimePurgePreviewProcessor,
                message -> setHeaders(message, effectiveArguments,
                    "requireClosed", "before", "from", "purgeBefore", "agentName", "agentType", "purgeAgentName",
                    "limit", "eventScanLimit", "conversationScanLimit", "includeConversationIds")));
            default -> writeError(exchange, error(-32601, "Unknown tool: " + toolName), 404);
        }
    }

    private Map<String, Object> invokeTool(Exchange exchange,
                                           String toolName,
                                           Map<String, Object> arguments,
                                           Processor delegate,
                                           ToolInputMapper inputMapper) throws Exception {
        DefaultExchange delegatedExchange = new DefaultExchange(exchange.getContext());
        Message delegatedIn = delegatedExchange.getIn();
        inputMapper.apply(delegatedIn);
        delegate.process(delegatedExchange);

        Message delegatedOut = delegatedExchange.getMessage();
        Integer status = delegatedOut.getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        if (status != null && status >= 400) {
            String rawError = delegatedOut.getBody(String.class);
            String message = extractErrorMessage(rawError);
            throw new IllegalArgumentException("Tool call failed for " + toolName + " (status=" + status + "): " + message);
        }

        String rawBody = delegatedOut.getBody(String.class);
        Map<String, Object> structuredContent = parseStructured(rawBody);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of(
            "type", "text",
            "text", rawBody == null ? "" : rawBody
        )));
        result.put("structuredContent", structuredContent);
        result.put("toolName", toolName);
        result.put("arguments", arguments);
        return result;
    }

    private void setHeaders(Message message, Map<String, Object> arguments, String... headerNames) {
        if (arguments == null || arguments.isEmpty() || headerNames == null) {
            return;
        }
        for (String header : headerNames) {
            if (header == null || header.isBlank()) {
                continue;
            }
            Object value = arguments.get(header);
            if (value != null) {
                message.setHeader(header, value);
            }
        }
    }

    private Map<String, Object> parseStructured(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawBody.getBytes(StandardCharsets.UTF_8), MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of("raw", rawBody);
        }
    }

    private String extractErrorMessage(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return "empty error response";
        }
        try {
            Map<String, Object> map = objectMapper.readValue(rawBody.getBytes(StandardCharsets.UTF_8), MAP_TYPE);
            Object message = map.get("message");
            if (message == null) {
                message = map.get("error");
            }
            return message == null ? rawBody : String.valueOf(message);
        } catch (Exception ignored) {
            return rawBody;
        }
    }

    private Map<String, Object> error(int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    @FunctionalInterface
    private interface ToolInputMapper {
        void apply(Message message) throws Exception;
    }
}
