-- Purge script (PostgreSQL)
-- Criteria:
--   1) closed conversations only (agent.instance.closed event)
--   2) closed before timestamp
--   3) specific agent type/name (from payload.agentName)
--
-- Usage in psql example:
--   \set purge_before_ts '2026-01-01T00:00:00Z'
--   \set purge_agent_name 'support-agent'
--   \set purge_require_closed 'true'

BEGIN;

WITH params AS (
    SELECT
        NULLIF(:'purge_before_ts', '')::timestamptz AS purge_before_ts,
        NULLIF(:'purge_agent_name', '')::text       AS purge_agent_name,
        COALESCE(NULLIF(:'purge_require_closed','')::boolean, true) AS purge_require_closed
),
closed_conv AS (
    SELECT DISTINCT e.flow_id AS conversation_id
    FROM camel_flow_event e
    WHERE e.flow_type = 'agent.conversation'
      AND e.event_type = 'agent.instance.closed'
      AND NULLIF(e.occurred_at, '')::timestamptz <= COALESCE((SELECT purge_before_ts FROM params), now())
),
agent_filtered AS (
    SELECT DISTINCT e.flow_id AS conversation_id
    FROM camel_flow_event e
    WHERE e.flow_type = 'agent.conversation'
      AND (
          (e.payload_json::jsonb #>> '{payload,agentName}') = (SELECT purge_agent_name FROM params)
          OR (e.payload_json::jsonb ->> 'agentName') = (SELECT purge_agent_name FROM params)
      )
),
target_conversations AS (
    SELECT DISTINCT e.flow_id AS conversation_id
    FROM camel_flow_event e
    CROSS JOIN params p
    WHERE e.flow_type = 'agent.conversation'
      AND (
          p.purge_before_ts IS NULL
          OR NULLIF(e.occurred_at, '')::timestamptz <= p.purge_before_ts
      )
      AND (
          p.purge_require_closed = false
          OR e.flow_id IN (SELECT conversation_id FROM closed_conv)
      )
      AND (
          p.purge_agent_name IS NULL
          OR e.flow_id IN (SELECT conversation_id FROM agent_filtered)
      )
),
target_tasks AS (
    SELECT s.flow_id AS task_id
    FROM camel_flow_snapshot s
    WHERE s.flow_type = 'agent.task'
      AND (s.snapshot_json::jsonb ->> 'conversationId') IN (SELECT conversation_id FROM target_conversations)
),
target_routes AS (
    SELECT s.flow_id AS route_instance_id
    FROM camel_flow_snapshot s
    WHERE s.flow_type = 'agent.dynamicRoute'
      AND (s.snapshot_json::jsonb ->> 'conversationId') IN (SELECT conversation_id FROM target_conversations)
)
SELECT 'target_conversations' AS metric, COUNT(*)::bigint AS value FROM target_conversations
UNION ALL
SELECT 'target_tasks', COUNT(*)::bigint FROM target_tasks
UNION ALL
SELECT 'target_routes', COUNT(*)::bigint FROM target_routes;

-- Remove conversation streams (persistence + audit)
DELETE FROM camel_flow_idempotency
WHERE flow_type IN ('agent.conversation', 'agent.chat.memory')
  AND flow_id IN (SELECT conversation_id FROM target_conversations);

DELETE FROM camel_flow_event
WHERE flow_type IN ('agent.conversation', 'agent.chat.memory')
  AND flow_id IN (SELECT conversation_id FROM target_conversations);

DELETE FROM camel_flow_snapshot
WHERE flow_type IN ('agent.conversation', 'agent.chat.memory')
  AND flow_id IN (SELECT conversation_id FROM target_conversations);

-- Remove task streams
DELETE FROM camel_flow_idempotency
WHERE flow_type IN ('agent.task', 'agent.task.lock')
  AND flow_id IN (SELECT task_id FROM target_tasks);

DELETE FROM camel_flow_event
WHERE flow_type IN ('agent.task', 'agent.task.lock')
  AND flow_id IN (SELECT task_id FROM target_tasks);

DELETE FROM camel_flow_snapshot
WHERE flow_type IN ('agent.task', 'agent.task.lock')
  AND flow_id IN (SELECT task_id FROM target_tasks);

-- Remove dynamic route streams
DELETE FROM camel_flow_idempotency
WHERE flow_type = 'agent.dynamicRoute'
  AND flow_id IN (SELECT route_instance_id FROM target_routes);

DELETE FROM camel_flow_event
WHERE flow_type = 'agent.dynamicRoute'
  AND flow_id IN (SELECT route_instance_id FROM target_routes);

DELETE FROM camel_flow_snapshot
WHERE flow_type = 'agent.dynamicRoute'
  AND flow_id IN (SELECT route_instance_id FROM target_routes);

-- Cleanup conversation index events
DELETE FROM camel_flow_event
WHERE flow_type = 'agent.conversation.index'
  AND (payload_json::jsonb ->> 'conversationId') IN (SELECT conversation_id FROM target_conversations);

-- Cleanup chat memory index snapshot array (agent.chat.memory.index / all)
UPDATE camel_flow_snapshot s
SET snapshot_json = (
        SELECT COALESCE(jsonb_agg(elem), '[]'::jsonb)::text
        FROM jsonb_array_elements(COALESCE(s.snapshot_json::jsonb, '[]'::jsonb)) AS elem
        WHERE elem::text <> ALL (
            SELECT to_jsonb(conversation_id)::text FROM target_conversations
        )
    ),
    version = s.version + 1,
    last_updated_at = now()::text
WHERE s.flow_type = 'agent.chat.memory.index'
  AND s.flow_id = 'all';

COMMIT;
