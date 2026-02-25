package io.dscope.camel.agent.samples;

import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        org.apache.camel.main.Main main = new org.apache.camel.main.Main();
        main.bind("supportUiPageProcessor", new SupportUiPageProcessor());
        main.bind("supportSipSessionInitEnvelopeProcessor", new SipSessionInitEnvelopeProcessor());
        main.bind("supportSipTranscriptFinalProcessor", new SipTranscriptFinalProcessor());
        main.bind("supportSipCallEndProcessor", new SipCallEndProcessor());
        AgentRuntimeBootstrap.bootstrap(main, "application.yaml");
        main.run(args);
    }
}
