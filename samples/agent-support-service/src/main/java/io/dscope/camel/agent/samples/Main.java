package io.dscope.camel.agent.samples;

import io.dscope.camel.agui.AgUiComponentApplicationSupport;
import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        org.apache.camel.main.Main main = new org.apache.camel.main.Main();
        AgUiComponentApplicationSupport support = new AgUiComponentApplicationSupport();
        support.bindDefaultBeans(main::bind);
        main.bind("agUiUiPageProcessor", new SupportUiPageProcessor());
        main.bind(AgUiComponentApplicationSupport.BEAN_AGENT_PRE_RUN_TEXT_PROCESSOR, new SupportAgUiPreRunTextProcessor());
        AgentRuntimeBootstrap.bootstrap(main, "application.yaml");
        main.run(args);
    }
}
