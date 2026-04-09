package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.audit.AuditConversationSipProcessor;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.SimpleRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TelephonyOnboardingHttpIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TEST_TWILIO_ACCOUNT_SID = "twilio-account-sid-test";
    private static final String ONBOARDING_REQUEST = """
        {
          "tenantId": "acme",
          "agentId": "support-voice",
          "displayName": "Support Voice",
          "publicBaseUrl": "https://voice.example.test",
          "openai": {
            "projectId": "proj_support",
            "apiKey": "openai-secret",
            "webhookSecret": "whsec_test_secret"
          },
          "twilio": {
            "accountSid": "twilio-account-sid-test",
            "authToken": "twilio-secret",
            "fromNumber": "+15557650002",
            "phoneNumber": "+15557650002",
            "trunkName": "support-voice-trunk"
          }
        }
        """;

    @Test
    void shouldPersistAndLoadReusableTelephonyOnboardingPlan() throws Exception {
        InMemoryPersistenceFacade persistenceFacade = new InMemoryPersistenceFacade();
        SimpleRegistry registry = new SimpleRegistry();
        registry.bind("persistenceFacade", persistenceFacade);
        registry.bind("objectMapper", MAPPER);

        try (DefaultCamelContext context = new DefaultCamelContext(registry)) {
            Properties properties = new Properties();
            properties.setProperty("agent.runtime.telephony.onboarding.tenant-id", "acme");
            properties.setProperty("agent.runtime.telephony.onboarding.agent-id", "support-voice");
            properties.setProperty("agent.runtime.a2a.public-base-url", "https://voice.example.test");
            properties.setProperty("agent.runtime.telephony.openai.project-id", "proj_default");
            properties.setProperty("agent.runtime.telephony.openai.instructions", "You are a helpful phone assistant.");
            properties.setProperty("agent.runtime.realtime.model", "gpt-realtime");
            properties.setProperty("agent.runtime.realtime.voice", "alloy");
            properties.setProperty("agent.runtime.telephony.twilio.from", "+15557650002");
            properties.setProperty("camel.component.twilio.account-sid", TEST_TWILIO_ACCOUNT_SID);
            properties.setProperty("camel.component.twilio.password", "test-token");
            properties.setProperty("openai.api.key", "test-openai-key");
            context.getPropertiesComponent().setInitialProperties(properties);
            context.start();

            SupportTelephonyOnboardingProcessor createProcessor = new SupportTelephonyOnboardingProcessor(MAPPER);
            SupportTelephonyOnboardingLookupProcessor lookupProcessor = new SupportTelephonyOnboardingLookupProcessor(MAPPER);
            AuditConversationSipProcessor sipProcessor = new AuditConversationSipProcessor(persistenceFacade, MAPPER);

            var createExchange = new DefaultExchange(context);
            createExchange.getMessage().setBody(ONBOARDING_REQUEST);

            createProcessor.process(createExchange);
            JsonNode createdBody = MAPPER.readTree(createExchange.getMessage().getBody(String.class));
            Assertions.assertTrue(createdBody.path("valid").asBoolean());
            Assertions.assertTrue(createdBody.path("persisted").asBoolean());
            Assertions.assertEquals("sip:proj_support@sip.api.openai.com;transport=tls", createdBody.path("openai").path("sipUri").asText());
            Assertions.assertEquals("telephony:onboarding:acme:support-voice", createdBody.path("configConversationId").asText());

            var lookupExchange = new DefaultExchange(context);
            lookupExchange.getMessage().setHeader("tenantId", "acme");
            lookupExchange.getMessage().setHeader("agentId", "support-voice");
            lookupProcessor.process(lookupExchange);
            JsonNode lookupBody = MAPPER.readTree(lookupExchange.getMessage().getBody(String.class));
            Assertions.assertEquals("sip-onboarding", lookupBody.path("conversationKind").asText());
            Assertions.assertEquals("proj_support", lookupBody.path("openai").path("projectId").asText());
            Assertions.assertEquals("support-voice-trunk", lookupBody.path("twilio").path("trunkName").asText());

            var auditExchange = new DefaultExchange(context);
            auditExchange.getMessage().setHeader("conversationId", "telephony:onboarding:acme:support-voice");
            sipProcessor.process(auditExchange);
            JsonNode auditBody = MAPPER.readTree(auditExchange.getMessage().getBody(String.class));
            Assertions.assertTrue(auditBody.path("present").asBoolean());
            Assertions.assertEquals("proj_support", auditBody.path("sip").path("openAiProjectId").asText());
            Assertions.assertEquals("support-voice-trunk", auditBody.path("sip").path("trunkName").asText());

            var streamingExchange = new DefaultExchange(context);
            streamingExchange.getMessage().setBody(new ByteArrayInputStream(ONBOARDING_REQUEST.getBytes(StandardCharsets.UTF_8)));
            createProcessor.process(streamingExchange);
            JsonNode streamingBody = MAPPER.readTree(streamingExchange.getMessage().getBody(String.class));
            Assertions.assertEquals("acme", streamingBody.path("tenantId").asText());
            Assertions.assertEquals("support-voice", streamingBody.path("agentId").asText());
            Assertions.assertEquals("sip:proj_support@sip.api.openai.com;transport=tls", streamingBody.path("openai").path("sipUri").asText());
        }
    }
}