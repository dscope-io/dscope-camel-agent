package io.dscope.camel.agent.starter;

public class AgentStarterMain {

    protected String defaultApplicationConfig() {
        return "application.yaml";
    }

    protected void run(String[] args) throws Exception {
        Main.run(args, defaultApplicationConfig());
    }
}