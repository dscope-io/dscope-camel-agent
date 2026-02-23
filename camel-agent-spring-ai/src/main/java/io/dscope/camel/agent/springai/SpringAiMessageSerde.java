package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

public class SpringAiMessageSerde {

    private final ObjectMapper objectMapper;

    public SpringAiMessageSerde(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode serialize(List<Message> messages) {
        ArrayNode result = objectMapper.createArrayNode();
        for (Message message : messages) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", message.getMessageType().name());
            node.put("text", message.getText());
            node.set("metadata", objectMapper.valueToTree(message.getMetadata()));

            if (message instanceof AssistantMessage assistantMessage) {
                ArrayNode toolCalls = objectMapper.createArrayNode();
                for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    ObjectNode tc = objectMapper.createObjectNode();
                    tc.put("id", toolCall.id());
                    tc.put("type", toolCall.type());
                    tc.put("name", toolCall.name());
                    tc.put("arguments", toolCall.arguments());
                    toolCalls.add(tc);
                }
                node.set("toolCalls", toolCalls);
            }

            if (message instanceof ToolResponseMessage toolResponseMessage) {
                ArrayNode responses = objectMapper.createArrayNode();
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    ObjectNode tr = objectMapper.createObjectNode();
                    tr.put("id", response.id());
                    tr.put("name", response.name());
                    tr.put("responseData", response.responseData());
                    responses.add(tr);
                }
                node.set("toolResponses", responses);
            }
            result.add(node);
        }
        return result;
    }

    public List<Message> deserialize(JsonNode jsonNode) {
        List<Message> messages = new ArrayList<>();
        if (jsonNode == null || !jsonNode.isArray()) {
            return messages;
        }

        for (JsonNode node : jsonNode) {
            String typeText = node.path("type").asText(MessageType.USER.name());
            MessageType type = parseType(typeText);
            String text = node.path("text").asText("");
            Map<String, Object> metadata = objectMapper.convertValue(node.path("metadata"), Map.class);

            switch (type) {
                case USER -> messages.add(UserMessage.builder().text(text).metadata(metadata).build());
                case SYSTEM -> messages.add(SystemMessage.builder().text(text).metadata(metadata).build());
                case ASSISTANT -> {
                    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
                    JsonNode tcNode = node.path("toolCalls");
                    if (tcNode.isArray()) {
                        for (JsonNode tc : tcNode) {
                            toolCalls.add(new AssistantMessage.ToolCall(
                                tc.path("id").asText(),
                                tc.path("type").asText(),
                                tc.path("name").asText(),
                                tc.path("arguments").asText()
                            ));
                        }
                    }
                    messages.add(new AssistantMessage(text, metadata, toolCalls));
                }
                case TOOL -> {
                    List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                    JsonNode trNode = node.path("toolResponses");
                    if (trNode.isArray()) {
                        for (JsonNode tr : trNode) {
                            responses.add(new ToolResponseMessage.ToolResponse(
                                tr.path("id").asText(),
                                tr.path("name").asText(),
                                tr.path("responseData").asText()
                            ));
                        }
                    }
                    messages.add(new ToolResponseMessage(responses, metadata));
                }
                default -> messages.add(UserMessage.builder().text(text).metadata(metadata).build());
            }
        }
        return messages;
    }

    private MessageType parseType(String text) {
        try {
            return MessageType.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return MessageType.fromValue(text);
        }
    }
}
