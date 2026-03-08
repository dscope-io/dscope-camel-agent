package io.dscope.camel.agent.component;

import org.apache.camel.spi.UriParam;
import org.apache.camel.spi.Metadata;

public class AgentConfiguration {

    @UriParam(defaultValue = "classpath:agents/support/agent.md")
    @Metadata(description = "Blueprint location, for example classpath:agents/support/agent.md.")
    private String blueprint = "classpath:agents/support/agent.md";

    @UriParam
    @Metadata(description = "Agent catalog configuration, for example classpath:agents/agents.yaml.")
    private String plansConfig;

    @UriParam(defaultValue = "redis_jdbc")
    @Metadata(description = "Persistence backend mode: redis, jdbc, redis_jdbc.")
    private String persistenceMode = "redis_jdbc";

    @UriParam(defaultValue = "true")
    @Metadata(description = "Enable strict schema validation for tool input and output.")
    private boolean strictSchema = true;

    @UriParam(defaultValue = "30000")
    @Metadata(description = "Tool execution timeout in milliseconds.")
    private long timeoutMs = 30_000L;

    @UriParam(defaultValue = "true")
    @Metadata(description = "Enable streaming token/event delivery.")
    private boolean streaming = true;

    public String getBlueprint() {
        return blueprint;
    }

    public void setBlueprint(String blueprint) {
        this.blueprint = blueprint;
    }

    public String getPlansConfig() {
        return plansConfig;
    }

    public void setPlansConfig(String plansConfig) {
        this.plansConfig = plansConfig;
    }

    public String getPersistenceMode() {
        return persistenceMode;
    }

    public void setPersistenceMode(String persistenceMode) {
        this.persistenceMode = persistenceMode;
    }

    public boolean isStrictSchema() {
        return strictSchema;
    }

    public void setStrictSchema(boolean strictSchema) {
        this.strictSchema = strictSchema;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }
}
