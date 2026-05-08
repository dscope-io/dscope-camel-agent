package io.dscope.camel.agent.agui;

import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgUiCurrentTurnProcessor implements Processor {

    private static final Logger LOG = LoggerFactory.getLogger(AgUiCurrentTurnProcessor.class);

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        if (exchange == null) {
            return;
        }

        Map<String, Object> params = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
        if (params == null || params.isEmpty()) {
            return;
        }

        String currentUserText = currentUserText(params.get("messages"));
        if (currentUserText.isBlank()) {
            LOG.info("AGUI current turn normalizer: no root user message found, paramsKeys={}", params.keySet());
            return;
        }

        params.put("text", currentUserText);
        exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);
        LOG.info("AGUI current turn normalizer: textChars={}, text={}", currentUserText.length(), excerpt(currentUserText));
    }

    private static String currentUserText(Object messagesObj) {
        if (!(messagesObj instanceof List<?> messages)) {
            return "";
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            Map<String, Object> message = map(messages.get(index));
            if (!"user".equalsIgnoreCase(stringValue(message.get("role")))) {
                continue;
            }
            String text = text(message);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String text(Map<String, Object> message) {
        String direct = stringValue(message.get("text"));
        if (!direct.isBlank()) {
            return direct;
        }
        Object content = message.get("content");
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            StringBuilder builder = new StringBuilder();
            for (Object partObj : parts) {
                Map<String, Object> part = map(partObj);
                String partText = stringValue(part.get("text"));
                if (!partText.isBlank()) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(partText);
                }
            }
            return builder.toString();
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String excerpt(String text) {
        String normalized = text.replace("\r", "\\r").replace("\n", "\\n");
        return normalized.length() <= 600 ? normalized : normalized.substring(0, 600) + "...";
    }
}