package io.dscope.camel.agent.starter;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "agent")
public class AgentStarterProperties {

    @NotBlank
    private String blueprint = "classpath:agents/agent.md";
    private String agentsConfig;
    private String persistenceMode = "redis_jdbc";
    private boolean strictSchema = true;
    @Min(1)
    private long timeoutMs = 30_000L;
    private boolean streaming = true;
    private String auditGranularity = "info";
    private String auditPersistenceBackend;
    private String auditJdbcUrl;
    private String auditJdbcUsername;
    private String auditJdbcPassword;
    private String auditJdbcDriverClassName;
    private boolean auditAsyncEnabled = true;
    @Min(1)
    private int auditAsyncQueueCapacity = 4096;
    @Min(10)
    private long auditAsyncRetryDelayMs = 250L;
    @Min(100)
    private long auditAsyncShutdownTimeoutMs = 5000L;
    @Min(1000)
    private long auditAsyncMetricsLogIntervalMs = 30000L;
    private boolean chatMemoryEnabled = true;
    @Min(1)
    private int chatMemoryWindow = 100;
    private boolean historyRehydrateFromPersistence = true;
    private String taskClaimOwnerId;
    @Min(1)
    private int taskClaimLeaseSeconds = 120;

    public String getBlueprint() {
        return blueprint;
    }

    public void setBlueprint(String blueprint) {
        this.blueprint = blueprint;
    }

    public String getAgentsConfig() {
        return agentsConfig;
    }

    public void setAgentsConfig(String agentsConfig) {
        this.agentsConfig = agentsConfig;
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

    public boolean isAuditAsyncEnabled() {
        return auditAsyncEnabled;
    }

    public void setAuditAsyncEnabled(boolean auditAsyncEnabled) {
        this.auditAsyncEnabled = auditAsyncEnabled;
    }

    public int getAuditAsyncQueueCapacity() {
        return auditAsyncQueueCapacity;
    }

    public void setAuditAsyncQueueCapacity(int auditAsyncQueueCapacity) {
        this.auditAsyncQueueCapacity = auditAsyncQueueCapacity;
    }

    public long getAuditAsyncRetryDelayMs() {
        return auditAsyncRetryDelayMs;
    }

    public void setAuditAsyncRetryDelayMs(long auditAsyncRetryDelayMs) {
        this.auditAsyncRetryDelayMs = auditAsyncRetryDelayMs;
    }

    public long getAuditAsyncShutdownTimeoutMs() {
        return auditAsyncShutdownTimeoutMs;
    }

    public void setAuditAsyncShutdownTimeoutMs(long auditAsyncShutdownTimeoutMs) {
        this.auditAsyncShutdownTimeoutMs = auditAsyncShutdownTimeoutMs;
    }

    public long getAuditAsyncMetricsLogIntervalMs() {
        return auditAsyncMetricsLogIntervalMs;
    }

    public void setAuditAsyncMetricsLogIntervalMs(long auditAsyncMetricsLogIntervalMs) {
        this.auditAsyncMetricsLogIntervalMs = auditAsyncMetricsLogIntervalMs;
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

    public boolean isHistoryRehydrateFromPersistence() {
        return historyRehydrateFromPersistence;
    }

    public void setHistoryRehydrateFromPersistence(boolean historyRehydrateFromPersistence) {
        this.historyRehydrateFromPersistence = historyRehydrateFromPersistence;
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
