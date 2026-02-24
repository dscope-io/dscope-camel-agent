package io.dscope.camel.agent.model;

public record RealtimeSpec(
    String provider,
    String model,
    String voice,
    String transport,
    String endpointUri,
    String inputAudioFormat,
    String outputAudioFormat,
    String retentionPolicy,
    Integer reconnectMaxSendRetries,
    Integer reconnectMaxReconnects,
    Long reconnectInitialBackoffMs,
    Long reconnectMaxBackoffMs
) {

    public RealtimeSpec(
        String provider,
        String model,
        String voice,
        String transport,
        String endpointUri,
        String inputAudioFormat,
        String outputAudioFormat,
        String retentionPolicy
    ) {
        this(provider, model, voice, transport, endpointUri, inputAudioFormat, outputAudioFormat, retentionPolicy, null, null, null, null);
    }
}
