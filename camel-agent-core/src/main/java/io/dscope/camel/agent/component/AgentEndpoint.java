package io.dscope.camel.agent.component;

import io.dscope.camel.agent.api.AgentKernel;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import org.apache.camel.Category;
import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.spi.Metadata;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriPath;
import org.apache.camel.spi.UriParam;
import org.apache.camel.support.DefaultEndpoint;

@UriEndpoint(firstVersion = "0.1.0", scheme = "agent", title = "Agent", syntax = "agent:agentId", category = {
    Category.AI,
    Category.CORE
})
public class AgentEndpoint extends DefaultEndpoint {

    @UriPath
    @Metadata(required = true, description = "Logical agent identifier.")
    private String agentId;

    @UriParam(defaultValue = "classpath:agents/agent.md")
    @Metadata(description = "Blueprint location, for example classpath:agents/agent.md.")
    private String blueprint = "classpath:agents/agent.md";

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
    private AgentKernel agentKernel;
    private CorrelationRegistry correlationRegistry;

    public AgentEndpoint(String endpointUri, AgentComponent component) {
        super(endpointUri, component);
    }

    @Override
    public Producer createProducer() {
        return new AgentProducer(this, agentKernel, correlationRegistry);
    }

    @Override
    public Consumer createConsumer(Processor processor) {
        throw new UnsupportedOperationException("agent consumer mode is not implemented in phase 1");
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

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

    public AgentKernel getAgentKernel() {
        return agentKernel;
    }

    public void setAgentKernel(AgentKernel agentKernel) {
        this.agentKernel = agentKernel;
    }

    public CorrelationRegistry getCorrelationRegistry() {
        return correlationRegistry;
    }

    public void setCorrelationRegistry(CorrelationRegistry correlationRegistry) {
        this.correlationRegistry = correlationRegistry;
    }
}
