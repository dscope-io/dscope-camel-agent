package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

class SpringAiMessageSerdeTest {

    @Test
    void shouldSerializeAndDeserializeMessages() {
        SpringAiMessageSerde serde = new SpringAiMessageSerde(new ObjectMapper());

        Message user = UserMessage.builder().text("hello").metadata(Map.of("lang", "en")).build();
        Message assistant = new AssistantMessage("hi", Map.of("m", "1"), List.of(
            new AssistantMessage.ToolCall("1", "function", "kb.search", "{\"q\":\"x\"}")
        ));
        Message tool = new ToolResponseMessage(List.of(
            new ToolResponseMessage.ToolResponse("1", "kb.search", "{\"answer\":\"ok\"}")
        ), Map.of("source", "tool"));

        var json = serde.serialize(List.of(user, assistant, tool));
        var roundTrip = serde.deserialize(json);

        Assertions.assertEquals(3, roundTrip.size());
        Assertions.assertEquals("hello", roundTrip.get(0).getText());
        Assertions.assertEquals("hi", roundTrip.get(1).getText());
        Assertions.assertEquals("", roundTrip.get(2).getText());
    }
}
