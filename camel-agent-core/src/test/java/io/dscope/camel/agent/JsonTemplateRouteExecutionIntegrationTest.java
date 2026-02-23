package io.dscope.camel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.executor.TemplateAwareCamelToolExecutor;
import io.dscope.camel.agent.kernel.DefaultAgentKernel;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.JsonRouteTemplateSpec;
import io.dscope.camel.agent.model.ModelOptions;
import io.dscope.camel.agent.model.ModelResponse;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.registry.DefaultToolRegistry;
import io.dscope.camel.agent.validation.SchemaValidator;
import java.util.List;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class JsonTemplateRouteExecutionIntegrationTest {

    @Test
    void shouldLoadAndExecuteGeneratedJsonRouteFromTemplate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();

        ToolSpec templateTool = new ToolSpec(
            "route.template.http.request",
            "Dynamic route template tool",
            null,
            null,
            mapper.readTree("""
                {
                  "type": "object",
                  "required": ["fromUri", "method", "url"],
                  "properties": {
                    "fromUri": {"type": "string"},
                    "method": {"type": "string"},
                    "url": {"type": "string"}
                  }
                }
                """),
            null,
            new ToolPolicy(false, 0, 30_000)
        );

        JsonRouteTemplateSpec templateSpec = new JsonRouteTemplateSpec(
            "http.request",
            "route.template.http.request",
            "Instantiate and execute dynamic JSON route",
            "fromUri",
            templateTool.inputSchema(),
            mapper.readTree("""
                {
                  "route": {
                    "id": "agent.dynamic.http.request",
                    "from": {
                      "uri": "{{fromUri}}",
                      "steps": [
                        {
                          "setHeader": {
                            "name": "CamelHttpMethod",
                            "constant": "{{method}}"
                          }
                        },
                        {
                          "toD": {
                            "uri": "{{url}}"
                          }
                        }
                      ]
                    }
                  }
                }
                """)
        );

        AgentBlueprint blueprint = new AgentBlueprint(
            "support",
            "0.1.0",
            "You are a support agent",
            List.of(templateTool),
            List.of(templateSpec)
        );

        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:dynamic-target")
                        .setBody(constant("dynamic-target-ok"));
                }
            });
            camelContext.start();

            ProducerTemplate producerTemplate = camelContext.createProducerTemplate();
            TemplateAwareCamelToolExecutor toolExecutor = new TemplateAwareCamelToolExecutor(
                camelContext,
                producerTemplate,
                mapper,
                blueprint.jsonRouteTemplates()
            );

            var toolArguments = mapper.readTree("""
                {
                  "fromUri": "direct:dynamic-http-start",
                  "method": "GET",
                  "url": "direct:dynamic-target",
                  "executeBody": {"requestId": "r1"}
                }
                """);

            AiModelClient aiModelClient = (String systemPrompt,
                                           List<io.dscope.camel.agent.model.AgentEvent> history,
                                           List<ToolSpec> tools,
                                           ModelOptions options,
                                           java.util.function.Consumer<String> callback) ->
                new ModelResponse(
                    "",
                    List.of(new AiToolCall(
                        "route.template.http.request",
                        toolArguments)),
                    true
                );

            DefaultAgentKernel kernel = new DefaultAgentKernel(
                blueprint,
                new DefaultToolRegistry(blueprint.tools()),
                toolExecutor,
                aiModelClient,
                persistence,
                new SchemaValidator(),
                mapper
            );

            var response = kernel.handleUserMessage("conv-json-1", "create and run route from template");

            var toolResultEvent = response.events().stream()
                .filter(e -> "tool.result".equals(e.type()))
                .findFirst()
                .orElseThrow();

            String executionContent = toolResultEvent.payload().path("data").path("executionResult").asText();
            Assertions.assertEquals("dynamic-target-ok", executionContent);

            var dynamicRoutes = persistence.loadDynamicRoutes(10);
            Assertions.assertFalse(dynamicRoutes.isEmpty());
            Assertions.assertEquals("http.request", dynamicRoutes.getFirst().templateId());
            Assertions.assertEquals("STARTED", dynamicRoutes.getFirst().status());
            Assertions.assertEquals("conv-json-1", dynamicRoutes.getFirst().conversationId());
        }
    }
}
