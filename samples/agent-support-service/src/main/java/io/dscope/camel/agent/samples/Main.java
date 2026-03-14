package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        org.apache.camel.main.Main main = new org.apache.camel.main.Main();
        main.bind("ticketLifecycleProcessor", new SupportTicketLifecycleProcessor(new ObjectMapper()));
        AgentRuntimeBootstrap.bootstrap(main, "application.yaml");
        SampleAdminMcpBindings.bindIfMissing(main, "application.yaml");
        main.run(args);
    }
}
