package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MultiProviderSpringAiChatGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldSanitizeOpenAiToolNamesAndRemapToolCallsToOriginalNames() throws Exception {
        MultiProviderSpringAiChatGateway gateway = new MultiProviderSpringAiChatGateway(new Properties());
        List<ToolSpec> tools = List.of(
            new ToolSpec("support.ticket.open", "", "support-ticket-open", null, null, null, new ToolPolicy(false, 0, 1000)),
            new ToolSpec("support ticket open", "", "support-ticket-open-2", null, null, null, new ToolPolicy(false, 0, 1000))
        );

        Method toolCallbacksForOpenAi = MultiProviderSpringAiChatGateway.class
            .getDeclaredMethod("toolCallbacksForOpenAi", List.class);
        toolCallbacksForOpenAi.setAccessible(true);

        Object openAiBundle = toolCallbacksForOpenAi.invoke(gateway, tools);

        Method aliasesMethod = openAiBundle.getClass().getDeclaredMethod("aliases");
        aliasesMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> aliases = (Map<String, String>) aliasesMethod.invoke(openAiBundle);

        Assertions.assertEquals(2, aliases.size());
        aliases.keySet().forEach(name -> Assertions.assertTrue(name.matches("^[a-zA-Z0-9_-]+$")));
        Assertions.assertTrue(aliases.containsValue("support.ticket.open"));
        Assertions.assertTrue(aliases.containsValue("support ticket open"));

        List<String> sanitizedNames = aliases.keySet().stream().toList();
        SpringAiChatGateway.SpringAiChatResult modelResult = new SpringAiChatGateway.SpringAiChatResult(
            "ok",
            List.of(
                new AiToolCall(sanitizedNames.get(0), MAPPER.createObjectNode()),
                new AiToolCall(sanitizedNames.get(1), MAPPER.createObjectNode())
            ),
            false
        );

        Method remapToolCallNames = MultiProviderSpringAiChatGateway.class
            .getDeclaredMethod("remapToolCallNames", SpringAiChatGateway.SpringAiChatResult.class, Map.class);
        remapToolCallNames.setAccessible(true);

        SpringAiChatGateway.SpringAiChatResult remapped = (SpringAiChatGateway.SpringAiChatResult) remapToolCallNames
            .invoke(gateway, modelResult, aliases);

        List<String> remappedNames = remapped.toolCalls().stream().map(AiToolCall::name).toList();
        Assertions.assertTrue(remappedNames.contains("support.ticket.open"));
        Assertions.assertTrue(remappedNames.contains("support ticket open"));
    }
}
