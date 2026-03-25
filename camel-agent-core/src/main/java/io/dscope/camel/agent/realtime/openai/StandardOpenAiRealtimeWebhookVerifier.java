package io.dscope.camel.agent.realtime.openai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class StandardOpenAiRealtimeWebhookVerifier implements OpenAiRealtimeWebhookVerifier {

    private static final String HEADER_ID = "webhook-id";
    private static final String HEADER_TIMESTAMP = "webhook-timestamp";
    private static final String HEADER_SIGNATURE = "webhook-signature";
    private static final String SECRET_PREFIX = "whsec_";
    private static final long DEFAULT_TOLERANCE_SECONDS = 300L;

    private final byte[] secret;
    private final Clock clock;
    private final long toleranceSeconds;

    public StandardOpenAiRealtimeWebhookVerifier(String secret) {
        this(secret, Clock.systemUTC(), DEFAULT_TOLERANCE_SECONDS);
    }

    StandardOpenAiRealtimeWebhookVerifier(String secret, Clock clock, long toleranceSeconds) {
        this.secret = decodeSecret(secret);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.toleranceSeconds = toleranceSeconds <= 0 ? DEFAULT_TOLERANCE_SECONDS : toleranceSeconds;
    }

    @Override
    public void verify(String payload, Map<String, String> headers) throws Exception {
        String body = payload == null ? "" : payload;
        String id = requiredHeader(headers, HEADER_ID);
        String timestampHeader = requiredHeader(headers, HEADER_TIMESTAMP);
        String signaturesHeader = requiredHeader(headers, HEADER_SIGNATURE);

        long timestamp = parseTimestamp(timestampHeader);
        ensureTimestampWithinTolerance(timestamp);

        String signedContent = id + "." + timestampHeader + "." + body;
        byte[] expected = hmacSha256(secret, signedContent.getBytes(StandardCharsets.UTF_8));
        List<byte[]> candidates = parseSignatures(signaturesHeader);
        for (byte[] candidate : candidates) {
            if (MessageDigest.isEqual(expected, candidate)) {
                return;
            }
        }
        throw new OpenAiRealtimeWebhookVerificationException("Invalid OpenAI webhook signature");
    }

    private void ensureTimestampWithinTolerance(long timestamp) {
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > toleranceSeconds) {
            throw new OpenAiRealtimeWebhookVerificationException("OpenAI webhook timestamp outside allowed tolerance");
        }
    }

    private long parseTimestamp(String timestampHeader) {
        try {
            return Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            throw new OpenAiRealtimeWebhookVerificationException("Invalid OpenAI webhook timestamp");
        }
    }

    private List<byte[]> parseSignatures(String signaturesHeader) {
        List<byte[]> signatures = new ArrayList<>();
        for (String token : signaturesHeader.trim().split("\\s+")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            int commaIndex = token.indexOf(',');
            if (commaIndex <= 0 || commaIndex == token.length() - 1) {
                continue;
            }
            String version = token.substring(0, commaIndex);
            if (!"v1".equals(version)) {
                continue;
            }
            String encoded = token.substring(commaIndex + 1);
            try {
                signatures.add(Base64.getDecoder().decode(encoded));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (signatures.isEmpty()) {
            throw new OpenAiRealtimeWebhookVerificationException("Missing supported OpenAI webhook signatures");
        }
        return signatures;
    }

    private static String requiredHeader(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            throw new OpenAiRealtimeWebhookVerificationException("Missing OpenAI webhook headers");
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(name)) {
                String value = entry.getValue();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
                break;
            }
        }
        throw new OpenAiRealtimeWebhookVerificationException("Missing required OpenAI webhook header: " + name);
    }

    private static byte[] decodeSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Missing OpenAI webhook secret");
        }
        String trimmed = secret.trim();
        String value = trimmed.startsWith(SECRET_PREFIX) ? trimmed.substring(SECRET_PREFIX.length()) : trimmed;
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] hmacSha256(byte[] secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(payload);
    }
}