package io.dscope.camel.agent.realtime;

public record RealtimeReconnectPolicy(
    int maxSendRetries,
    int maxReconnectsPerSession,
    long initialBackoffMs,
    long maxBackoffMs
) {

    public long backoffDelayMs(int attempt) {
        long initial = Math.max(1L, initialBackoffMs);
        long cappedMax = Math.max(initial, maxBackoffMs);
        long exponential = initial << Math.max(0, attempt - 1);
        return Math.min(exponential, cappedMax);
    }

    public RealtimeReconnectPolicy normalized() {
        int retries = Math.max(0, maxSendRetries);
        int reconnects = Math.max(1, maxReconnectsPerSession);
        long initial = Math.max(1L, initialBackoffMs);
        long max = Math.max(initial, maxBackoffMs);
        return new RealtimeReconnectPolicy(retries, reconnects, initial, max);
    }
}
