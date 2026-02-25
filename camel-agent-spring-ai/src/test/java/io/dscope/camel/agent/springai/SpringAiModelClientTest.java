package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.ModelOptions;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SpringAiModelClientTest {

    @Test
    void shouldDelegateToGateway() {
        SpringAiChatGateway gateway = (systemPrompt, userContext, tools, model, temperature, maxTokens, callback) -> {
            callback.accept("hi");
            return new SpringAiChatGateway.SpringAiChatResult("done", List.of(new AiToolCall("demo", new ObjectMapper().createObjectNode())), true);
        };
        SpringAiModelClient client = new SpringAiModelClient(gateway, new ObjectMapper());

        var response = client.generate(
            "system",
            List.of(),
            List.of(new ToolSpec("demo", "", "r1", null, null, null, new ToolPolicy(false, 0, 1000))),
            ModelOptions.defaults(),
            token -> { }
        );

        Assertions.assertEquals("done", response.assistantMessage());
        Assertions.assertEquals(1, response.toolCalls().size());
    }

    @Test
    void shouldForwardConversationHistoryAsGatewayContext() {
        ObjectMapper mapper = new ObjectMapper();
        SpringAiChatGateway gateway = (systemPrompt, userContext, tools, model, temperature, maxTokens, callback) -> {
            Assertions.assertEquals("system", systemPrompt);
            Assertions.assertTrue(userContext.contains("user.message: \"first turn\""));
            Assertions.assertTrue(userContext.contains("assistant.message: \"first response\""));
            return new SpringAiChatGateway.SpringAiChatResult("done", List.of(), true);
        };

        SpringAiModelClient client = new SpringAiModelClient(gateway, mapper);
        List<AgentEvent> history = List.of(
            new AgentEvent("conv-1", null, "user.message", mapper.valueToTree("first turn"), Instant.now()),
            new AgentEvent("conv-1", null, "assistant.message", mapper.valueToTree("first response"), Instant.now())
        );

        var response = client.generate(
            "system",
            history,
            List.of(new ToolSpec("demo", "", "r1", null, null, null, new ToolPolicy(false, 0, 1000))),
            ModelOptions.defaults(),
            token -> {
            }
        );

        Assertions.assertEquals("done", response.assistantMessage());
    }
}
