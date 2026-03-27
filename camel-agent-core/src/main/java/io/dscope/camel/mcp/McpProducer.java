package io.dscope.camel.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.mcp.model.McpRequest;
import io.dscope.camel.mcp.model.McpResponse;
import io.dscope.camel.mcp.processor.McpHttpValidatorProcessor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultProducer;

/**
 * MCP producer patched for tolerant response parsing.
 */
public class McpProducer extends DefaultProducer {
    public static final String HEADER_METHOD = "CamelMcpMethod";
    public static final String HEADER_PROTOCOL_VERSION = "CamelMcpProtocolVersion";
    private static final String LOCAL_URI_PREFIX = "camel:";
    private static final String MCP_ACCEPT = "application/json, text/event-stream";
    private static final String JSON_CONTENT_TYPE = "application/json";

    private final McpEndpoint endpoint;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpProducer(McpEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        McpConfiguration cfg = endpoint.getConfiguration();

        McpRequest req = new McpRequest();
        req.setJsonrpc("2.0");
        req.setId(UUID.randomUUID().toString());
        req.setMethod(resolveMethod(exchange, cfg));
        req.setParams(resolveParams(exchange));

        McpResponse resp = dispatchByUriStructure(cfg.getUri(), req);
        exchange.getMessage().setBody(resp);
    }

    private McpResponse dispatchByUriStructure(String targetUri, McpRequest req) throws Exception {
        if (targetUri != null && targetUri.startsWith(LOCAL_URI_PREFIX)) {
            String localUri = targetUri.substring(LOCAL_URI_PREFIX.length());
            Object localResponse = endpoint.getCamelContext()
                .createProducerTemplate()
                .requestBody(localUri, req, Object.class);
            return toMcpResponse(localResponse, req.getId());
        }

        String json = mapper.writeValueAsString(req);
        Map<String, Object> transportHeaders = buildRemoteTransportHeaders();
        ProducerTemplate template = endpoint.getCamelContext().createProducerTemplate();
        Object rawResponse = template.requestBodyAndHeaders(targetUri, json, transportHeaders, Object.class);
        return toMcpResponse(rawResponse, req.getId());
    }

    private McpResponse toMcpResponse(Object responseBody, String requestId) {
        if (responseBody == null) {
            throw new IllegalStateException("MCP response body is null");
        }
        if (responseBody instanceof McpResponse mcpResponse) {
            return mcpResponse;
        }

        JsonNode responseNode = toJsonNode(responseBody);
        if (responseNode != null && responseNode.isObject() && responseNode.has("result")) {
            return mapper.convertValue(responseNode, McpResponse.class);
        }

        McpResponse response = new McpResponse();
        response.setJsonrpc("2.0");
        response.setId(requestId);
        response.setResult(responseNode == null ? mapper.nullNode() : responseNode);
        return response;
    }

    private JsonNode toJsonNode(Object responseBody) {
        if (responseBody instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        try {
            String json = endpoint.getCamelContext().getTypeConverter().mandatoryConvertTo(String.class, responseBody);
            return mapper.readTree(json);
        } catch (Exception converterFailure) {
            if (responseBody instanceof String json) {
                try {
                    return mapper.readTree(json);
                } catch (Exception parsingFailure) {
                    throw new IllegalStateException("Failed to parse MCP response JSON", parsingFailure);
                }
            }
            Object converted = endpoint.getCamelContext().getTypeConverter().tryConvertTo(Map.class, responseBody);
            if (converted != null) {
                return mapper.valueToTree(converted);
            }
            converted = endpoint.getCamelContext().getTypeConverter().tryConvertTo(java.util.List.class, responseBody);
            if (converted != null) {
                return mapper.valueToTree(converted);
            }
            throw new IllegalStateException("Failed to convert MCP response body to JSON node: " + responseBody.getClass().getName(), converterFailure);
        }
    }

    private String resolveMethod(Exchange exchange, McpConfiguration cfg) {
        String headerMethod = exchange.getIn().getHeader(HEADER_METHOD, String.class);
        if (headerMethod != null && !headerMethod.isBlank()) {
            return headerMethod;
        }
        return cfg.getMethod();
    }

    private Map<String, Object> resolveParams(Exchange exchange) {
        Object body = exchange.getIn().getBody();
        if (body == null) {
            return Map.of();
        }
        if (!(body instanceof Map<?, ?> rawParams)) {
            throw new IllegalArgumentException(
                "MCP producer expects exchange body to be a Map<String,Object> (or null) to populate JSON-RPC params");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        rawParams.forEach((key, value) -> params.put(String.valueOf(key), value));
        return params;
    }

    private Map<String, Object> buildRemoteTransportHeaders() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(Exchange.HTTP_METHOD, "POST");
        headers.put("Accept", MCP_ACCEPT);
        headers.put("Content-Type", JSON_CONTENT_TYPE);

        String protocolVersion = resolveProtocolVersion();
        if (protocolVersion != null && !protocolVersion.isBlank()) {
            headers.put("MCP-Protocol-Version", protocolVersion);
        }
        return headers;
    }

    private String resolveProtocolVersion() {
        String propertyValue = endpoint.getCamelContext()
                .getGlobalOption(McpHttpValidatorProcessor.EXCHANGE_PROTOCOL_VERSION);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }

        String systemValue = System.getProperty(HEADER_PROTOCOL_VERSION);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }

        return null;
    }
}
