package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.realtime.OpenAiRealtimeRelayClient;
import io.dscope.camel.agent.realtime.openai.HttpOpenAiRealtimeCallControlClient;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallControlClient;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallControlRequestFactory;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallSessionRegistry;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeWebhookVerifier;
import io.dscope.camel.agent.realtime.openai.StandardOpenAiRealtimeWebhookVerifier;
import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;
import io.dscope.camel.agent.twilio.TwilioCallStartEnvelopeProcessor;
import io.dscope.camel.agent.twilio.TwilioTranscriptEnvelopeProcessor;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        org.apache.camel.main.Main main = new org.apache.camel.main.Main();
        ObjectMapper objectMapper = new ObjectMapper();
        SupportCallRegistry supportCallRegistry = new SupportCallRegistry();
        OpenAiRealtimeCallSessionRegistry openAiCallSessionRegistry = new OpenAiRealtimeCallSessionRegistry();
        OpenAiRealtimeCallControlRequestFactory openAiRequestFactory = new OpenAiRealtimeCallControlRequestFactory();
        OpenAiRealtimeRelayClient openAiRealtimeRelayClient = new OpenAiRealtimeRelayClient();
        OpenAiRealtimeWebhookVerifier webhookVerifier = createWebhookVerifier();
        OpenAiRealtimeCallControlClient callControlClient = createCallControlClient();

        main.bind("ticketLifecycleProcessor", new SupportTicketLifecycleProcessor(objectMapper));
        main.bind("agUiPlanVersionSelector", new AgUiPlanVersionSelectorProcessor());
        main.bind("supportCallRegistry", supportCallRegistry);
        main.bind("openAiRealtimeCallSessionRegistry", openAiCallSessionRegistry);
        main.bind("openAiRealtimeCallControlRequestFactory", openAiRequestFactory);
        main.bind("openAiRealtimeRelayClient", openAiRealtimeRelayClient);
        if (webhookVerifier != null) {
            main.bind("openAiRealtimeWebhookVerifier", webhookVerifier);
        }
        if (callControlClient != null) {
            main.bind("openAiRealtimeCallControlClient", callControlClient);
        }
        main.bind("supportOutboundCallProcessor", new SupportOutboundCallProcessor(objectMapper, supportCallRegistry));
        main.bind(
            "supportOpenAiSipWebhookProcessor",
            new SupportOpenAiSipWebhookProcessor(
                objectMapper,
                supportCallRegistry,
                webhookVerifier,
                callControlClient,
                openAiRequestFactory,
                openAiCallSessionRegistry,
                openAiRealtimeRelayClient
            )
        );
        main.bind("supportTwilioCallStartEnvelopeProcessor", new TwilioCallStartEnvelopeProcessor());
        main.bind("supportTwilioTranscriptEnvelopeProcessor", new TwilioTranscriptEnvelopeProcessor());
        AgentRuntimeBootstrap.bootstrap(main, "application.yaml");
        SampleAdminMcpBindings.bindIfMissing(main, "application.yaml");
        main.run(args);
    }

    private static OpenAiRealtimeWebhookVerifier createWebhookVerifier() {
        String secret = firstNonBlank(
            System.getenv("OPENAI_WEBHOOK_SECRET"),
            System.getProperty("openai.webhook.secret")
        );
        return secret == null ? null : new StandardOpenAiRealtimeWebhookVerifier(secret);
    }

    private static OpenAiRealtimeCallControlClient createCallControlClient() {
        String apiKey = firstNonBlank(
            System.getenv("OPENAI_API_KEY"),
            System.getProperty("openai.api.key")
        );
        return apiKey == null ? null : new HttpOpenAiRealtimeCallControlClient(apiKey);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
