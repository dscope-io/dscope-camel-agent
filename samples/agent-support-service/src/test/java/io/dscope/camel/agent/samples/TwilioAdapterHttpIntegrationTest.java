package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.realtime.sip.SipCallEndProcessor;
import io.dscope.camel.agent.realtime.sip.SipSessionInitEnvelopeProcessor;
import io.dscope.camel.agent.realtime.sip.SipTranscriptFinalProcessor;
import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;
import io.dscope.camel.agent.twilio.TwilioCallStartEnvelopeProcessor;
import io.dscope.camel.agent.twilio.TwilioTranscriptEnvelopeProcessor;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.main.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TwilioAdapterHttpIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldMapTwilioCallStartIntoRealtimeInitEnvelope() throws Exception {
        int port = randomPort();
        String previousPort = System.getProperty("agent.runtime.test-port");
        System.setProperty("agent.runtime.test-port", Integer.toString(port));

        Main main = new Main();
        main.bind("supportTwilioCallStartEnvelopeProcessor", new TwilioCallStartEnvelopeProcessor());
        main.bind("supportTwilioTranscriptEnvelopeProcessor", new TwilioTranscriptEnvelopeProcessor());
        main.bind("supportSipSessionInitEnvelopeProcessor", new SipSessionInitEnvelopeProcessor());
        main.bind("supportSipTranscriptFinalProcessor", new SipTranscriptFinalProcessor());
        main.bind("supportSipCallEndProcessor", new SipCallEndProcessor());
        main.bind("supportRealtimeSessionInitProcessor", new EchoJsonProcessor("init"));
        main.bind("supportRealtimeEventProcessorCore", new EchoJsonProcessor("event"));

        try {
            AgentRuntimeBootstrap.bootstrap(main, "twilio-adapter-http-test.yaml");
            main.start();

            HttpResult response = postJson(
                port,
                "/twilio/adapter/v1/call/CA0001/start",
                """
                    {
                      "from": "+15551230001",
                      "to": "+15557650002",
                      "voice": "Polly.Joanna",
                      "twilio": {
                        "accountSid": "AC123",
                        "streamSid": "MZ456"
                      }
                    }
                    """
            );

            Assertions.assertEquals(200, response.statusCode());
            JsonNode body = MAPPER.readTree(response.body());
            JsonNode forwarded = body.path("forwarded");
            Assertions.assertEquals("init", body.path("processor").asText());
            Assertions.assertEquals("sip:twilio:CA0001", forwarded.path("session").path("metadata").path("sip").path("conversationId").asText());
            Assertions.assertEquals("CA0001", forwarded.path("session").path("metadata").path("sip").path("callId").asText());
            Assertions.assertEquals("AC123", forwarded.path("session").path("metadata").path("twilio").path("accountSid").asText());
            Assertions.assertEquals("Polly.Joanna", forwarded.path("session").path("audio").path("output").path("voice").asText());
        } finally {
            safeStop(main);
            restorePort(previousPort);
        }
    }

    @Test
    void shouldMapTwilioTranscriptIntoRealtimeEvent() throws Exception {
        int port = randomPort();
        String previousPort = System.getProperty("agent.runtime.test-port");
        System.setProperty("agent.runtime.test-port", Integer.toString(port));

        Main main = new Main();
        main.bind("supportTwilioCallStartEnvelopeProcessor", new TwilioCallStartEnvelopeProcessor());
        main.bind("supportTwilioTranscriptEnvelopeProcessor", new TwilioTranscriptEnvelopeProcessor());
        main.bind("supportSipSessionInitEnvelopeProcessor", new SipSessionInitEnvelopeProcessor());
        main.bind("supportSipTranscriptFinalProcessor", new SipTranscriptFinalProcessor());
        main.bind("supportSipCallEndProcessor", new SipCallEndProcessor());
        main.bind("supportRealtimeSessionInitProcessor", new EchoJsonProcessor("init"));
        main.bind("supportRealtimeEventProcessorCore", new EchoJsonProcessor("event"));

        try {
            AgentRuntimeBootstrap.bootstrap(main, "twilio-adapter-http-test.yaml");
            main.start();

            HttpResult response = postJson(
                port,
                "/twilio/adapter/v1/call/CA0002/turn",
                """
                    {
                      "media": {
                        "transcript": "my login keeps failing"
                      }
                    }
                    """
            );

            Assertions.assertEquals(200, response.statusCode());
            JsonNode body = MAPPER.readTree(response.body());
            Assertions.assertEquals("event", body.path("processor").asText());
            Assertions.assertEquals("transcript.final", body.path("forwarded").path("type").asText());
            Assertions.assertEquals("my login keeps failing", body.path("forwarded").path("text").asText());
        } finally {
            safeStop(main);
            restorePort(previousPort);
        }
    }

    @Test
    void shouldAcknowledgeTwilioCallEnd() throws Exception {
        int port = randomPort();
        String previousPort = System.getProperty("agent.runtime.test-port");
        System.setProperty("agent.runtime.test-port", Integer.toString(port));

        Main main = new Main();
        main.bind("supportTwilioCallStartEnvelopeProcessor", new TwilioCallStartEnvelopeProcessor());
        main.bind("supportTwilioTranscriptEnvelopeProcessor", new TwilioTranscriptEnvelopeProcessor());
        main.bind("supportSipSessionInitEnvelopeProcessor", new SipSessionInitEnvelopeProcessor());
        main.bind("supportSipTranscriptFinalProcessor", new SipTranscriptFinalProcessor());
        main.bind("supportSipCallEndProcessor", new SipCallEndProcessor());
        main.bind("supportRealtimeSessionInitProcessor", new EchoJsonProcessor("init"));
        main.bind("supportRealtimeEventProcessorCore", new EchoJsonProcessor("event"));

        try {
            AgentRuntimeBootstrap.bootstrap(main, "twilio-adapter-http-test.yaml");
            main.start();

            HttpResult response = postJson(
                port,
                "/twilio/adapter/v1/call/CA0003/end",
                "{}"
            );

            Assertions.assertEquals(200, response.statusCode());
            JsonNode body = MAPPER.readTree(response.body());
            Assertions.assertEquals("sip:twilio:CA0003", body.path("conversationId").asText());
            Assertions.assertTrue(body.path("ended").asBoolean());
        } finally {
            safeStop(main);
            restorePort(previousPort);
        }
    }

    private static HttpResult postJson(int port, String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.body() == null ? "" : response.body());
    }

    private static int randomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void safeStop(Main main) {
        try {
            main.stop();
        } catch (Exception ignored) {
        }
    }

    private static void restorePort(String previousPort) {
        if (previousPort == null) {
            System.clearProperty("agent.runtime.test-port");
            return;
        }
        System.setProperty("agent.runtime.test-port", previousPort);
    }

    private record HttpResult(int statusCode, String body) {
    }

    private static final class EchoJsonProcessor implements Processor {

        private final String name;

        private EchoJsonProcessor(String name) {
            this.name = name;
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            JsonNode incoming = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            ObjectNode response = MAPPER.createObjectNode();
            response.put("processor", name);
            response.set("forwarded", incoming);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            exchange.getMessage().setBody(MAPPER.writeValueAsString(response));
        }
    }
}