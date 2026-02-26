-- DScope Camel Persistence + Audit schema (PostgreSQL)
-- Compatible with JdbcFlowStateStore table contracts:
--   camel_flow_snapshot, camel_flow_event, camel_flow_idempotency

CREATE TABLE IF NOT EXISTS camel_flow_snapshot (
    flow_type       VARCHAR(128) NOT NULL,
    flow_id         VARCHAR(256) NOT NULL,
    version         BIGINT NOT NULL,
    snapshot_json   TEXT NOT NULL,
    metadata_json   TEXT NOT NULL,
    last_updated_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (flow_type, flow_id)
);

CREATE TABLE IF NOT EXISTS camel_flow_event (
    flow_type        VARCHAR(128) NOT NULL,
    flow_id          VARCHAR(256) NOT NULL,
    sequence         BIGINT NOT NULL,
    event_id         VARCHAR(128) NOT NULL,
    event_type       VARCHAR(128) NOT NULL,
    payload_json     TEXT NOT NULL,
    occurred_at      VARCHAR(64) NOT NULL,
    idempotency_key  VARCHAR(256),
    PRIMARY KEY (flow_type, flow_id, sequence)
);

CREATE TABLE IF NOT EXISTS camel_flow_idempotency (
    flow_type       VARCHAR(128) NOT NULL,
    flow_id         VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    applied_version BIGINT NOT NULL,
    PRIMARY KEY (flow_type, flow_id, idempotency_key)
);

-- Performance indexes for audit/purge and conversation queries
CREATE INDEX IF NOT EXISTS idx_camel_flow_event_type_time
    ON camel_flow_event (event_type, occurred_at);

CREATE INDEX IF NOT EXISTS idx_camel_flow_event_flow
    ON camel_flow_event (flow_type, flow_id);

CREATE INDEX IF NOT EXISTS idx_camel_flow_snapshot_flow
    ON camel_flow_snapshot (flow_type, flow_id);

-- Optional JSON helper indexes (requires payload_json to be valid JSON)
-- CREATE INDEX IF NOT EXISTS idx_camel_flow_event_payload_jsonb
--     ON camel_flow_event USING GIN ((payload_json::jsonb));
