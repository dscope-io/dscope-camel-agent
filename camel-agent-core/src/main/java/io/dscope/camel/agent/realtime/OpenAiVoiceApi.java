package io.dscope.camel.agent.realtime;

import java.util.Locale;

public enum OpenAiVoiceApi {
    REALTIME("realtime", "gpt-realtime-2.1"),
    LIVE("live", "gpt-live-1");

    private final String configValue;
    private final String defaultModel;

    OpenAiVoiceApi(String configValue, String defaultModel) {
        this.configValue = configValue;
        this.defaultModel = defaultModel;
    }

    public String configValue() {
        return configValue;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public static OpenAiVoiceApi fromConfig(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "realtime" -> REALTIME;
            case "live", "gpt-live" -> LIVE;
            default -> throw new IllegalArgumentException(
                "Unsupported OpenAI voice API '" + value + "'; expected realtime or live");
        };
    }

    public String validateModel(String model) {
        String resolved = model == null || model.isBlank() ? defaultModel : model.trim();
        boolean valid = this == LIVE
            ? resolved.startsWith("gpt-live-")
            : resolved.startsWith("gpt-realtime-");
        if (!valid) {
            throw new IllegalArgumentException(
                "Model '" + resolved + "' is not compatible with OpenAI voice API '" + configValue + "'");
        }
        return resolved;
    }
}
