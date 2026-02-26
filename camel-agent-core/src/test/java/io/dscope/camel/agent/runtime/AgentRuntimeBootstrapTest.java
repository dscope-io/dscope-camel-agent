package io.dscope.camel.agent.runtime;

import io.dscope.camel.agent.realtime.OpenAiRealtimeRelayClient;
import io.dscope.camel.agent.realtime.RealtimeBrowserSessionInitProcessor;
import io.dscope.camel.agent.realtime.RealtimeBrowserTokenProcessor;
import io.dscope.camel.agent.realtime.RealtimeEventProcessor;
import org.apache.camel.main.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentRuntimeBootstrapTest {

    @Test
    void shouldAutoBindRealtimeRelayAndProcessorWhenMissing() throws Exception {
        Main main = new Main();

        AgentRuntimeBootstrap.bootstrap(main, "bootstrap-bindings-test.yaml");

        OpenAiRealtimeRelayClient relayClient = main.lookup("openAiRealtimeRelayClient", OpenAiRealtimeRelayClient.class);
        RealtimeEventProcessor processor = main.lookup("supportRealtimeEventProcessor", RealtimeEventProcessor.class);

        Assertions.assertNotNull(relayClient);
        Assertions.assertNotNull(processor);
    }

    @Test
    void shouldNotOverridePreboundRealtimeRelayAndProcessor() throws Exception {
        Main main = new Main();
        OpenAiRealtimeRelayClient existingRelay = new OpenAiRealtimeRelayClient();
        RealtimeEventProcessor existingProcessor = new RealtimeEventProcessor(existingRelay, "direct:existing");

        main.bind("openAiRealtimeRelayClient", existingRelay);
        main.bind("supportRealtimeEventProcessor", existingProcessor);

        AgentRuntimeBootstrap.bootstrap(main, "bootstrap-bindings-test.yaml");

        OpenAiRealtimeRelayClient relayClient = main.lookup("openAiRealtimeRelayClient", OpenAiRealtimeRelayClient.class);
        RealtimeEventProcessor processor = main.lookup("supportRealtimeEventProcessor", RealtimeEventProcessor.class);

        Assertions.assertSame(existingRelay, relayClient);
        Assertions.assertSame(existingProcessor, processor);
    }

    @Test
    void shouldSkipRealtimeBindingsWhenDisabled() throws Exception {
        Main main = new Main();

        AgentRuntimeBootstrap.bootstrap(main, "bootstrap-bindings-disabled-test.yaml");

        OpenAiRealtimeRelayClient relayClient = main.lookup("openAiRealtimeRelayClient", OpenAiRealtimeRelayClient.class);
        RealtimeEventProcessor processor = main.lookup("supportRealtimeEventProcessor", RealtimeEventProcessor.class);

        Assertions.assertNull(relayClient);
        Assertions.assertNull(processor);
    }

    @Test
    void shouldReplacePreboundRealtimeInitAndTokenProcessors() throws Exception {
        Main main = new Main();

        main.bind("supportRealtimeSessionInitProcessor", new Object());
        main.bind("supportRealtimeTokenProcessor", new Object());

        String property = "agent.runtime.realtime.prefer-core-token-processor";
        String previous = System.getProperty(property);
        System.setProperty(property, "true");
        try {
            AgentRuntimeBootstrap.bootstrap(main, "bootstrap-bindings-test.yaml");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }

        RealtimeBrowserSessionInitProcessor initProcessor = main.lookup("supportRealtimeSessionInitProcessor", RealtimeBrowserSessionInitProcessor.class);
        RealtimeBrowserTokenProcessor tokenProcessor = main.lookup("supportRealtimeTokenProcessor", RealtimeBrowserTokenProcessor.class);

        Assertions.assertNotNull(initProcessor);
        Assertions.assertNotNull(tokenProcessor);
    }

}
