package io.dscope.camel.agent.realtime.openai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class StandardOpenAiRealtimeWebhookVerifierTest {

    @Test
    void verifiesValidV1Signature() throws Exception {
        String secretValue = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        String secret = "whsec_" + secretValue;
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_750_287_078L), ZoneOffset.UTC);
        StandardOpenAiRealtimeWebhookVerifier verifier = new StandardOpenAiRealtimeWebhookVerifier(secret, clock, 300L);
        String payload = "{\"object\":\"event\",\"type\":\"realtime.call.incoming\"}";
        String id = "wh_123";
        String timestamp = "1750287078";
        String signature = sign(secretValue, id + "." + timestamp + "." + payload);

        assertDoesNotThrow(() -> verifier.verify(payload, Map.of(
            "webhook-id", id,
            "webhook-timestamp", timestamp,
            "webhook-signature", "v1," + signature
        )));
    }

    @Test
    void rejectsInvalidSignature() {
        String secretValue = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_750_287_078L), ZoneOffset.UTC);
        StandardOpenAiRealtimeWebhookVerifier verifier = new StandardOpenAiRealtimeWebhookVerifier("whsec_" + secretValue, clock, 300L);

        assertThrows(OpenAiRealtimeWebhookVerificationException.class, () -> verifier.verify("{}", Map.of(
            "webhook-id", "wh_123",
            "webhook-timestamp", "1750287078",
            "webhook-signature", "v1," + Base64.getEncoder().encodeToString("bad".getBytes(StandardCharsets.UTF_8))
        )));
    }

    @Test
    void rejectsExpiredTimestamp() throws Exception {
        String secretValue = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_750_287_078L), ZoneOffset.UTC);
        StandardOpenAiRealtimeWebhookVerifier verifier = new StandardOpenAiRealtimeWebhookVerifier("whsec_" + secretValue, clock, 10L);
        String payload = "{}";
        String id = "wh_123";
        String timestamp = "1750287000";
        String signature = sign(secretValue, id + "." + timestamp + "." + payload);

        assertThrows(OpenAiRealtimeWebhookVerificationException.class, () -> verifier.verify(payload, Map.of(
            "webhook-id", id,
            "webhook-timestamp", timestamp,
            "webhook-signature", "v1," + signature
        )));
    }

    private static String sign(String base64Secret, String content) throws Exception {
        byte[] secret = Base64.getDecoder().decode(base64Secret);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] signature = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature);
    }
}