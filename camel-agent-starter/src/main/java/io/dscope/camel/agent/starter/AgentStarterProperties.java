package io.dscope.camel.agent.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent")
public class AgentStarterProperties {

    private String blueprint = "classpath:agents/support/agent.md";
    private String persistenceMode = "redis_jdbc";
    private boolean strictSchema = true;
    private long timeoutMs = 30_000L;
    private boolean streaming = true;
    private String auditGranularity = "info";
    private String auditPersistenceBackend;
    private String auditJdbcUrl;
    private String auditJdbcUsername;
    private String auditJdbcPassword;
    private String auditJdbcDriverClassName;
    private boolean chatMemoryEnabled = true;
    private int chatMemoryWindow = 100;
    private String taskClaimOwnerId;
    private int taskClaimLeaseSeconds = 120;

    public String getBlueprint() {
        return blueprint;
    }

    public void setBlueprint(String blueprint) {
        this.blueprint = blueprint;
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

    public String getAuditGranularity() {
        return auditGranularity;
    }

    public void setAuditGranularity(String auditGranularity) {
        this.auditGranularity = auditGranularity;
    }

    public String getAuditPersistenceBackend() {
        return auditPersistenceBackend;
    }

    public void setAuditPersistenceBackend(String auditPersistenceBackend) {
        this.auditPersistenceBackend = auditPersistenceBackend;
    }

    public String getAuditJdbcUrl() {
        return auditJdbcUrl;
    }

    public void setAuditJdbcUrl(String auditJdbcUrl) {
        this.auditJdbcUrl = auditJdbcUrl;
    }

    public String getAuditJdbcUsername() {
        return auditJdbcUsername;
    }

    public void setAuditJdbcUsername(String auditJdbcUsername) {
        this.auditJdbcUsername = auditJdbcUsername;
    }

    public String getAuditJdbcPassword() {
        return auditJdbcPassword;
    }

    public void setAuditJdbcPassword(String auditJdbcPassword) {
        this.auditJdbcPassword = auditJdbcPassword;
    }

    public String getAuditJdbcDriverClassName() {
        return auditJdbcDriverClassName;
    }

    public void setAuditJdbcDriverClassName(String auditJdbcDriverClassName) {
        this.auditJdbcDriverClassName = auditJdbcDriverClassName;
    }

    public boolean isChatMemoryEnabled() {
        return chatMemoryEnabled;
    }

    public void setChatMemoryEnabled(boolean chatMemoryEnabled) {
        this.chatMemoryEnabled = chatMemoryEnabled;
    }

    public int getChatMemoryWindow() {
        return chatMemoryWindow;
    }

    public void setChatMemoryWindow(int chatMemoryWindow) {
        this.chatMemoryWindow = chatMemoryWindow;
    }

    public String getTaskClaimOwnerId() {
        return taskClaimOwnerId;
    }

    public void setTaskClaimOwnerId(String taskClaimOwnerId) {
        this.taskClaimOwnerId = taskClaimOwnerId;
    }

    public int getTaskClaimLeaseSeconds() {
        return taskClaimLeaseSeconds;
    }

    public void setTaskClaimLeaseSeconds(int taskClaimLeaseSeconds) {
        this.taskClaimLeaseSeconds = taskClaimLeaseSeconds;
    }
}
