package io.dscope.camel.agent.runtime;

import io.dscope.camel.agent.model.RealtimeSpec;
import java.util.function.Function;

public final class RealtimeConfigResolver {

    private RealtimeConfigResolver() {
    }

    public static RealtimeSpec resolve(RealtimeSpec blueprintSpec, Function<String, String> propertyLookup) {
        if (propertyLookup == null) {
            return blueprintSpec;
        }

        String provider = firstNonBlank(
            value(blueprintSpec == null ? null : blueprintSpec.provider()),
            property(propertyLookup, "agent.runtime.realtime.provider"),
            property(propertyLookup, "agent.realtime.provider")
        );
        String model = firstNonBlank(
            value(blueprintSpec == null ? null : blueprintSpec.model()),
            property(propertyLookup, "agent.runtime.realtime.model"),
            property(propertyLookup, "agent.realtime.model")
        );
        String voice = firstNonBlank(
            value(blueprintSpec == null ? null : blueprintSpec.voice()),
            property(propertyLookup, "agent.runtime.realtime.voice"),
            property(propertyLookup, "agent.realtime.voice")
        );
        String transport = firstNonBlank(
            value(blueprintSpec == null ? null : blueprintSpec.transport()),
            property(propertyLookup, "agent.runtime.realtime.transport"),
            property(propertyLookup, "agent.realtime.transport")
        );
        String endpointUri = firstNonBlank(
            value(blueprintSpec == null ? null : blueprintSpec.endpointUri()),
            property(propertyLookup, "agent.runtime.realtime.endpoint-uri"),
            property(propertyLookup, "agent.runtime.realtime.endpointUri"),
            property(propertyLookup, "agent.realtime.endpoint-uri"),
            property(propertyLookup, "agent.realtime.endpointUri")
        );
        String inputAudioFormat = firstNonBlank(
            value(blueprintSpec == null ? null : blueprintSpec.inputAudioFormat()),
            property(propertyLookup, "agent.runtime.realtime.input-audio-format"),
            property(propertyLookup, "agent.runtime.realtime.inputAudioFormat"),
            property(propertyLookup, "agent.realtime.input-audio-format"),
            property(propertyLookup, "agent.realtime.inputAudioFormat")
        );
        String outputAudioFormat = firstNonBlank(
            value(blueprintSpec == null ? null : blueprintSpec.outputAudioFormat()),
            property(propertyLookup, "agent.runtime.realtime.output-audio-format"),
            property(propertyLookup, "agent.runtime.realtime.outputAudioFormat"),
            property(propertyLookup, "agent.realtime.output-audio-format"),
            property(propertyLookup, "agent.realtime.outputAudioFormat")
        );
        String retentionPolicy = firstNonBlank(
            value(blueprintSpec == null ? null : blueprintSpec.retentionPolicy()),
            property(propertyLookup, "agent.runtime.realtime.retention-policy"),
            property(propertyLookup, "agent.runtime.realtime.retentionPolicy"),
            property(propertyLookup, "agent.realtime.retention-policy"),
            property(propertyLookup, "agent.realtime.retentionPolicy")
        );
        Integer reconnectMaxSendRetries = firstNonNull(
            integer(blueprintSpec == null ? null : blueprintSpec.reconnectMaxSendRetries()),
            propertyInteger(propertyLookup, "agent.runtime.realtime.reconnect.max-send-retries"),
            propertyInteger(propertyLookup, "agent.runtime.realtime.reconnect.maxSendRetries"),
            propertyInteger(propertyLookup, "agent.realtime.reconnect.max-send-retries"),
            propertyInteger(propertyLookup, "agent.realtime.reconnect.maxSendRetries")
        );
        Integer reconnectMaxReconnects = firstNonNull(
            integer(blueprintSpec == null ? null : blueprintSpec.reconnectMaxReconnects()),
            propertyInteger(propertyLookup, "agent.runtime.realtime.reconnect.max-reconnects"),
            propertyInteger(propertyLookup, "agent.runtime.realtime.reconnect.maxReconnects"),
            propertyInteger(propertyLookup, "agent.realtime.reconnect.max-reconnects"),
            propertyInteger(propertyLookup, "agent.realtime.reconnect.maxReconnects")
        );
        Long reconnectInitialBackoffMs = firstNonNull(
            longValue(blueprintSpec == null ? null : blueprintSpec.reconnectInitialBackoffMs()),
            propertyLong(propertyLookup, "agent.runtime.realtime.reconnect.initial-backoff-ms"),
            propertyLong(propertyLookup, "agent.runtime.realtime.reconnect.initialBackoffMs"),
            propertyLong(propertyLookup, "agent.realtime.reconnect.initial-backoff-ms"),
            propertyLong(propertyLookup, "agent.realtime.reconnect.initialBackoffMs")
        );
        Long reconnectMaxBackoffMs = firstNonNull(
            longValue(blueprintSpec == null ? null : blueprintSpec.reconnectMaxBackoffMs()),
            propertyLong(propertyLookup, "agent.runtime.realtime.reconnect.max-backoff-ms"),
            propertyLong(propertyLookup, "agent.runtime.realtime.reconnect.maxBackoffMs"),
            propertyLong(propertyLookup, "agent.realtime.reconnect.max-backoff-ms"),
            propertyLong(propertyLookup, "agent.realtime.reconnect.maxBackoffMs")
        );

        if (allBlank(provider, model, voice, transport, endpointUri, inputAudioFormat, outputAudioFormat, retentionPolicy)
            && allNull(reconnectMaxSendRetries, reconnectMaxReconnects, reconnectInitialBackoffMs, reconnectMaxBackoffMs)) {
            return null;
        }

        return new RealtimeSpec(
            provider,
            model,
            voice,
            transport,
            endpointUri,
            inputAudioFormat,
            outputAudioFormat,
            retentionPolicy,
            reconnectMaxSendRetries,
            reconnectMaxReconnects,
            reconnectInitialBackoffMs,
            reconnectMaxBackoffMs
        );
    }

    private static String property(Function<String, String> lookup, String key) {
        return value(lookup.apply(key));
    }

    private static Integer propertyInteger(Function<String, String> lookup, String key) {
        String value = value(lookup.apply(key));
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long propertyLong(Function<String, String> lookup, String key) {
        String value = value(lookup.apply(key));
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String value(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean allBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static boolean allNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return false;
            }
        }
        return true;
    }

    private static Integer integer(Integer value) {
        return value;
    }

    private static Long longValue(Long value) {
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
