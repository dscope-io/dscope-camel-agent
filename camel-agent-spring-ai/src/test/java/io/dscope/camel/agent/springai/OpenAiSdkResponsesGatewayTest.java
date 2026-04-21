package io.dscope.camel.agent.springai;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenAiSdkResponsesGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldAddPromptCacheFieldsAndMapResponseUsage() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.openai.prompt-cache.enabled", "true");
        properties.setProperty("agent.runtime.spring-ai.openai.prompt-cache.key", "support-cache-v1");
        properties.setProperty("agent.runtime.spring-ai.openai.prompt-cache.retention", "24h");
        properties.setProperty("agent.runtime.model-cost.openai.gpt-5-4.prompt-usd-per-1m", "1.00");
        properties.setProperty("agent.runtime.model-cost.openai.gpt-5-4.completion-usd-per-1m", "2.00");

        AtomicReference<ResponseCreateParams> captured = new AtomicReference<>();
        OpenAiSdkResponsesGateway gateway = new OpenAiSdkResponsesGateway(properties, (request, apiKey, baseUrl, organization, project) -> {
            captured.set(request);
            return responseFixture();
        });

        SpringAiChatGateway.SpringAiChatResult result = gateway.generate(
            "responses-http",
            "system rules",
            "user question",
            java.util.List.of(),
            "gpt-5.4",
            0.2,
            250,
            "test-key",
            "https://api.openai.com",
            null
        );

        ResponseCreateParams request = captured.get();
        Assertions.assertNotNull(request);
        Assertions.assertEquals("support-cache-v1", request._additionalBodyProperties().get("prompt_cache_key").convert(String.class));
        Assertions.assertEquals("24h", request._additionalBodyProperties().get("prompt_cache_retention").convert(String.class));
        Assertions.assertTrue(result.message().contains("OpenAI responses HTTP invocation error"));
    }

    @Test
    void shouldMarkResponseFunctionToolsStrict() throws Exception {
        AtomicReference<ResponseCreateParams> captured = new AtomicReference<>();
        OpenAiSdkResponsesGateway gateway = new OpenAiSdkResponsesGateway(new Properties(), (request, apiKey, baseUrl, organization, project) -> {
            captured.set(request);
            return responseFixture();
        });

        gateway.generate(
            "responses-http",
            "system rules",
            "user question",
            List.of(new ToolSpec(
                "customerLookup",
                "Lookup customers",
                null,
                null,
                MAPPER.readTree("""
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {
                        "email": {"type": "string"}
                      }
                    }
                    """),
                null,
                new ToolPolicy(false, 0, 1000)
            )),
            "gpt-5.4",
            null,
            null,
            "test-key",
            "https://api.openai.com",
            null
        );

        ResponseCreateParams request = captured.get();
        Assertions.assertNotNull(request);
        Assertions.assertTrue(request.toString().contains("strict=true"));
    }

    @Test
    void shouldPopulateRequiredArrayForStrictObjectToolSchemas() throws Exception {
        AtomicReference<ResponseCreateParams> captured = new AtomicReference<>();
        OpenAiSdkResponsesGateway gateway = new OpenAiSdkResponsesGateway(new Properties(), (request, apiKey, baseUrl, organization, project) -> {
            captured.set(request);
            return responseFixture();
        });

        gateway.generate(
            "responses-http",
            "system rules",
            "find a customer",
            List.of(new ToolSpec(
                "customerLookup",
                "Lookup customers",
                null,
                null,
                MAPPER.readTree("""
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {
                        "email": {"type": "string"},
                        "phone": {"type": "string"}
                      }
                    }
                    """),
                null,
                new ToolPolicy(false, 0, 1000)
            )),
            "gpt-5.4",
            null,
            null,
            "test-key",
            "https://api.openai.com",
            null
        );

        ResponseCreateParams request = captured.get();
        Assertions.assertNotNull(request);
        String serialized = request.toString();
        Assertions.assertTrue(serialized.contains("additionalProperties=false"));
        Assertions.assertTrue(serialized.contains("required=[email, phone]") || serialized.contains("required=[phone, email]"));
    }

        @Test
        void shouldPopulateNestedObjectSchemaRequirementsForStrictTools() throws Exception {
                AtomicReference<ResponseCreateParams> captured = new AtomicReference<>();
                OpenAiSdkResponsesGateway gateway = new OpenAiSdkResponsesGateway(new Properties(), (request, apiKey, baseUrl, organization, project) -> {
                        captured.set(request);
                        return responseFixture();
                });

                gateway.generate(
                        "responses-http",
                        "system rules",
                        "invoke nested schema tool",
                        List.of(new ToolSpec(
                                "routeTemplateHttpRequest",
                                "Invoke a route template",
                                null,
                                null,
                                MAPPER.readTree("""
                                        {
                                            "type": "object",
                                            "properties": {
                                                "executeBody": {
                                                    "type": "object",
                                                    "properties": {
                                                        "url": {"type": "string"},
                                                        "method": {"type": "string"}
                                                    }
                                                }
                                            }
                                        }
                                        """),
                                null,
                                new ToolPolicy(false, 0, 1000)
                        )),
                        "gpt-5.4",
                        null,
                        null,
                        "test-key",
                        "https://api.openai.com",
                        null
                );

                ResponseCreateParams request = captured.get();
                Assertions.assertNotNull(request);
                String serialized = request.toString();
                Assertions.assertTrue(serialized.contains("executeBody={type=object, properties={url={type=string}, method={type=string}}, additionalProperties=false"));
                Assertions.assertTrue(serialized.contains("required=[url, method]") || serialized.contains("required=[method, url]"));
        }

            @Test
            void shouldForceAdditionalPropertiesFalseForUntypedNestedObjectParameters() throws Exception {
                AtomicReference<ResponseCreateParams> captured = new AtomicReference<>();
                OpenAiSdkResponsesGateway gateway = new OpenAiSdkResponsesGateway(new Properties(), (request, apiKey, baseUrl, organization, project) -> {
                    captured.set(request);
                    return responseFixture();
                });

                gateway.generate(
                    "responses-http",
                    "system rules",
                    "invoke template tool",
                    List.of(new ToolSpec(
                        "routeTemplateHttpRequest",
                        "Invoke a route template",
                        null,
                        null,
                        MAPPER.readTree("""
                            {
                              "type": "object",
                              "properties": {
                                "executeBody": {
                                  "type": "object",
                                  "description": "Optional body sent when invoking generated route"
                                }
                              }
                            }
                            """),
                        null,
                        new ToolPolicy(false, 0, 1000)
                    )),
                    "gpt-5.4",
                    null,
                    null,
                    "test-key",
                    "https://api.openai.com",
                    null
                );

                ResponseCreateParams request = captured.get();
                Assertions.assertNotNull(request);
                Assertions.assertTrue(request.toString().contains("executeBody={type=object, description=Optional body sent when invoking generated route, additionalProperties=false, properties={}, required=[]}"));
            }

    private Response responseFixture() {
        return Response.builder().build();
    }
}