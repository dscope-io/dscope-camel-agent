package io.dscope.camel.agent.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.a2a.A2AToolContext;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.JsonRouteTemplateSpec;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.Builder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;

public class TemplateAwareCamelToolExecutor implements ToolExecutor {

    private static final Set<String> DISALLOWED_DSL_KEYS = Set.of("process", "script", "groovy");

    private final CamelToolExecutor delegate;
    private final CamelContext camelContext;
    private final ProducerTemplate producerTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, JsonRouteTemplateSpec> templatesByToolName;

    public TemplateAwareCamelToolExecutor(CamelContext camelContext,
                                          ProducerTemplate producerTemplate,
                                          ObjectMapper objectMapper,
                                          List<JsonRouteTemplateSpec> templates) {
        this(camelContext, producerTemplate, objectMapper, templates, null, A2AToolContext.EMPTY);
    }

    public TemplateAwareCamelToolExecutor(CamelContext camelContext,
                                          ProducerTemplate producerTemplate,
                                          ObjectMapper objectMapper,
                                          List<JsonRouteTemplateSpec> templates,
                                          PersistenceFacade persistenceFacade,
                                          A2AToolContext a2aToolContext) {
        this.camelContext = camelContext;
        this.producerTemplate = producerTemplate;
        this.objectMapper = objectMapper;
        this.delegate = new CamelToolExecutor(camelContext, producerTemplate, objectMapper, persistenceFacade, a2aToolContext);
        this.templatesByToolName = new HashMap<>();
        if (templates != null) {
            for (JsonRouteTemplateSpec template : templates) {
                this.templatesByToolName.put(template.toolName(), template);
            }
        }
    }

    @Override
    public ToolResult execute(ToolSpec toolSpec, JsonNode arguments, ExecutionContext context) {
        JsonRouteTemplateSpec template = templatesByToolName.get(toolSpec.name());
        if (template == null) {
            return delegate.execute(toolSpec, arguments, context);
        }
        return executeTemplate(template, arguments, context);
    }

    private ToolResult executeTemplate(JsonRouteTemplateSpec template, JsonNode arguments, ExecutionContext context) {
        JsonNode args = arguments == null || arguments.isNull() ? objectMapper.createObjectNode() : arguments;
        JsonNode renderedRoute = renderTemplate(template.routeTemplate(), args);
        validateJsonDsl(renderedRoute);

        String routeId = ensureRouteId(renderedRoute, template.id());
        String routeInstanceId = UUID.randomUUID().toString();
        try {
            synchronized (camelContext) {
                camelContext.addRoutes(routeBuilderFromJson(renderedRoute));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load generated JSON route for template: " + template.id(), e);
        }

        String invokeUri = resolveInvokeUri(template, renderedRoute, args);
        JsonNode executionResult = objectMapper.nullNode();
        if (invokeUri != null && !invokeUri.isBlank()) {
            Map<String, Object> headers = new HashMap<>();
            headers.put(AgentHeaders.CONVERSATION_ID, context.conversationId());
            headers.put(AgentHeaders.TASK_ID, context.taskId());
            headers.put(AgentHeaders.TOOL_NAME, template.toolName());
            headers.put(AgentHeaders.TRACE_ID, context.traceId());
            Object response = producerTemplate.requestBodyAndHeaders(invokeUri, requestBody(args.path("executeBody")), headers);
            executionResult = objectMapper.valueToTree(response);
        }

        ObjectNode dynamicRoute = objectMapper.createObjectNode()
            .put("routeInstanceId", routeInstanceId)
            .put("templateId", template.id())
            .put("routeId", routeId)
            .put("status", "STARTED")
            .put("conversationId", context.conversationId())
            .put("createdAt", Instant.now().toString())
            .put("expiresAt", Instant.now().plus(1, ChronoUnit.HOURS).toString());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("dynamicRoute", dynamicRoute);
        payload.set("renderedRoute", renderedRoute);
        payload.set("executionResult", executionResult);

        String content = "Dynamic route " + routeId + " loaded";
        if (invokeUri != null && !invokeUri.isBlank()) {
            content += " and executed via " + invokeUri;
        }
        return new ToolResult(content, payload, List.of());
    }

    private JsonNode renderTemplate(JsonNode node, JsonNode arguments) {
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode objectNode = objectMapper.createObjectNode();
            node.properties().forEach(entry -> objectNode.set(entry.getKey(), renderTemplate(entry.getValue(), arguments)));
            return objectNode;
        }
        if (node.isArray()) {
            ArrayNode arrayNode = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                arrayNode.add(renderTemplate(item, arguments));
            }
            return arrayNode;
        }
        if (node.isTextual()) {
            String rendered = node.asText();
            if (arguments != null && arguments.isObject()) {
                for (Map.Entry<String, JsonNode> entry : arguments.properties()) {
                    String placeholder = "{{" + entry.getKey() + "}}";
                    String replacement = entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString();
                    rendered = rendered.replace(placeholder, replacement);
                }
                return objectMapper.getNodeFactory().textNode(rendered);
            }
            return objectMapper.getNodeFactory().textNode(rendered);
        }
        return node.deepCopy();
    }

    private String ensureRouteId(JsonNode renderedRoute, String templateId) {
        JsonNode route = renderedRoute.path("route");
        if (!route.isObject()) {
            throw new IllegalArgumentException("Generated JSON DSL must contain top-level 'route' object");
        }
        JsonNode id = route.path("id");
        if (id.isTextual() && !id.asText().isBlank()) {
            return id.asText();
        }
        String generated = "agent.dynamic." + templateId.replace(' ', '.') + "." + UUID.randomUUID().toString().substring(0, 8);
        ((ObjectNode) route).put("id", generated);
        return generated;
    }

    private String resolveInvokeUri(JsonRouteTemplateSpec template, JsonNode renderedRoute, JsonNode arguments) {
        String invokeParam = template.invokeUriParam() == null || template.invokeUriParam().isBlank()
            ? "fromUri"
            : template.invokeUriParam();
        String fromArgs = arguments.path(invokeParam).asText(null);
        if (fromArgs != null && !fromArgs.isBlank()) {
            return fromArgs;
        }
        return renderedRoute.path("route").path("from").path("uri").asText(null);
    }

    private void validateJsonDsl(JsonNode route) {
        if (!route.isObject()) {
            throw new IllegalArgumentException("Generated JSON route must be a JSON object");
        }
        if (!route.has("route")) {
            throw new IllegalArgumentException("Generated JSON DSL must contain 'route'");
        }
        validateDisallowedKeys(route);
    }

    private RouteBuilder routeBuilderFromJson(JsonNode renderedRoute) {
        return new RouteBuilder() {
            @Override
            public void configure() {
                JsonNode routeNode = renderedRoute.path("route");
                String fromUri = routeNode.path("from").path("uri").asText(null);
                if (fromUri == null || fromUri.isBlank()) {
                    throw new IllegalArgumentException("Generated route is missing from.uri");
                }
                String routeId = routeNode.path("id").asText();
                RouteDefinition route = from(fromUri).routeId(routeId);

                JsonNode steps = routeNode.path("from").path("steps");
                if (!steps.isArray()) {
                    return;
                }
                ProcessorDefinition<?> current = route;
                for (JsonNode step : steps) {
                    current = applyStep(current, step);
                }
            }

            private ProcessorDefinition<?> applyStep(ProcessorDefinition<?> current, JsonNode step) {
                if (!step.isObject() || step.size() != 1) {
                    throw new IllegalArgumentException("Invalid JSON DSL step format: " + step);
                }
                Map.Entry<String, JsonNode> entry = step.properties().iterator().next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();

                return switch (key) {
                    case "setHeader" -> {
                        String name = value.path("name").asText(null);
                        if (name == null || name.isBlank()) {
                            throw new IllegalArgumentException("setHeader step missing name");
                        }
                        if (value.has("constant")) {
                            yield current.setHeader(name, Builder.constant(value.path("constant").asText()));
                        }
                        if (value.has("simple")) {
                            yield current.setHeader(name, Builder.simple(value.path("simple").asText()));
                        }
                        throw new IllegalArgumentException("setHeader must provide constant or simple value");
                    }
                    case "to" -> {
                        String uri = value.path("uri").asText(null);
                        if (uri == null || uri.isBlank()) {
                            throw new IllegalArgumentException("to step missing uri");
                        }
                        yield current.to(uri);
                    }
                    case "toD" -> {
                        String uri = value.path("uri").asText(null);
                        if (uri == null || uri.isBlank()) {
                            throw new IllegalArgumentException("toD step missing uri");
                        }
                        yield current.toD(uri);
                    }
                    case "log" -> {
                        String message = value.path("message").asText(null);
                        if (message == null) {
                            throw new IllegalArgumentException("log step missing message");
                        }
                        yield current.log(message);
                    }
                    default -> throw new IllegalArgumentException("Unsupported JSON DSL step key: " + key);
                };
            }
        };
    }

    private void validateDisallowedKeys(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (DISALLOWED_DSL_KEYS.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Generated JSON DSL contains disallowed key: " + entry.getKey());
                }
                validateDisallowedKeys(entry.getValue());
            });
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                validateDisallowedKeys(item);
            }
        }
    }

    private Object requestBody(JsonNode arguments) {
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) {
            return null;
        }
        if (arguments.isValueNode()) {
            return arguments.asText();
        }
        return objectMapper.convertValue(arguments, Object.class);
    }
}
