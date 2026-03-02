-- DScope JDBC flow-state schema for Snowflake
CREATE TABLE IF NOT EXISTS camel_flow_snapshot (
    flow_type       STRING NOT NULL,
    flow_id         STRING NOT NULL,
    version         NUMBER(38,0) NOT NULL,
    snapshot_json   STRING NOT NULL,
    metadata_json   STRING NOT NULL,
    last_updated_at STRING NOT NULL,
    PRIMARY KEY (flow_type, flow_id)
);

CREATE TABLE IF NOT EXISTS camel_flow_event (
    flow_type        STRING NOT NULL,
    flow_id          STRING NOT NULL,
    sequence         NUMBER(38,0) NOT NULL,
    event_id         STRING NOT NULL,
    event_type       STRING NOT NULL,
    payload_json     STRING NOT NULL,
    occurred_at      STRING NOT NULL,
    idempotency_key  STRING,
    PRIMARY KEY (flow_type, flow_id, sequence)
);

CREATE TABLE IF NOT EXISTS camel_flow_idempotency (
    flow_type       STRING NOT NULL,
    flow_id         STRING NOT NULL,
    idempotency_key STRING NOT NULL,
    applied_version NUMBER(38,0) NOT NULL,
    PRIMARY KEY (flow_type, flow_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_camel_flow_event_type_time
    ON camel_flow_event (event_type, occurred_at);

CREATE INDEX IF NOT EXISTS idx_camel_flow_event_flow
    ON camel_flow_event (flow_type, flow_id);

CREATE INDEX IF NOT EXISTS idx_camel_flow_snapshot_flow
    ON camel_flow_snapshot (flow_type, flow_id);
