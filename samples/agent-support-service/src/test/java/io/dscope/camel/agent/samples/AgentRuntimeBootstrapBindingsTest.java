package io.dscope.camel.agent.samples;

import io.dscope.camel.agui.AgUiComponentApplicationSupport;
import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;
import org.apache.camel.main.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentRuntimeBootstrapBindingsTest {

    @Test
    void shouldSkipAgUiAndRealtimeBindingsWhenDisabled() throws Exception {
        Main main = new Main();

        AgentRuntimeBootstrap.bootstrap(main, "bootstrap-bindings-disabled-sample.yaml");

        Object preRunProcessor = main.lookup(AgUiComponentApplicationSupport.BEAN_AGENT_PRE_RUN_TEXT_PROCESSOR, Object.class);
        Object realtimeRelay = main.lookup("openAiRealtimeRelayClient", Object.class);
        Object realtimeProcessor = main.lookup("supportRealtimeEventProcessor", Object.class);

        Assertions.assertNull(preRunProcessor);
        Assertions.assertNull(realtimeRelay);
        Assertions.assertNull(realtimeProcessor);
    }

    @Test
    void shouldBindSipProcessorsWhenEnabledInSampleConfig() throws Exception {
        Main main = new Main();

        AgentRuntimeBootstrap.bootstrap(main, "application.yaml");

        Assertions.assertNotNull(main.lookup("supportSipSessionInitEnvelopeProcessor", Object.class));
        Assertions.assertNotNull(main.lookup("supportSipTranscriptFinalProcessor", Object.class));
        Assertions.assertNotNull(main.lookup("supportSipCallEndProcessor", Object.class));
    }
}
