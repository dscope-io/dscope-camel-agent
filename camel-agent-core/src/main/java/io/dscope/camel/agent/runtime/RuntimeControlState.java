package io.dscope.camel.agent.runtime;

import io.dscope.camel.agent.model.AuditGranularity;

public class RuntimeControlState {

    private volatile AuditGranularity auditGranularity;

    public RuntimeControlState(AuditGranularity initialGranularity) {
        this.auditGranularity = initialGranularity == null ? AuditGranularity.INFO : initialGranularity;
    }

    public AuditGranularity auditGranularity() {
        return auditGranularity;
    }

    public void setAuditGranularity(AuditGranularity granularity) {
        this.auditGranularity = granularity == null ? AuditGranularity.INFO : granularity;
    }
}
