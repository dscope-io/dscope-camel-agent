package io.dscope.camel.agent.persistence.dscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.dscope.camel.persistence.core.AppendResult;
import io.dscope.camel.persistence.core.FlowStateStore;
import io.dscope.camel.persistence.core.PersistedEvent;
import io.dscope.camel.persistence.core.RehydratedState;
import io.dscope.camel.persistence.core.StateEnvelope;
import io.dscope.camel.persistence.core.exception.BackendUnavailableException;
import io.dscope.camel.persistence.core.exception.OptimisticConflictException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PostgresTextJdbcFlowStateStore implements FlowStateStore {

    private static final int DEFAULT_REHYDRATE_LIMIT = 100_000;

    private static final String CREATE_SNAPSHOT = """
        CREATE TABLE IF NOT EXISTS camel_flow_snapshot (
            flow_type       VARCHAR(128) NOT NULL,
            flow_id         VARCHAR(256) NOT NULL,
            version         BIGINT NOT NULL,
            snapshot_json   TEXT NOT NULL,
            metadata_json   TEXT NOT NULL,
            last_updated_at VARCHAR(64) NOT NULL,
            PRIMARY KEY (flow_type, flow_id)
        )
        """;

    private static final String CREATE_EVENT = """
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
        )
        """;

    private static final String CREATE_IDEMPOTENCY = """
        CREATE TABLE IF NOT EXISTS camel_flow_idempotency (
            flow_type       VARCHAR(128) NOT NULL,
            flow_id         VARCHAR(256) NOT NULL,
            idempotency_key VARCHAR(256) NOT NULL,
            applied_version BIGINT NOT NULL,
            PRIMARY KEY (flow_type, flow_id, idempotency_key)
        )
        """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;

    PostgresTextJdbcFlowStateStore(String jdbcUrl, String jdbcUser, String jdbcPassword) {
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser == null ? "" : jdbcUser;
        this.jdbcPassword = jdbcPassword == null ? "" : jdbcPassword;
        initializeSchema();
    }

    @Override
    public RehydratedState rehydrate(String flowType, String flowId) {
        try (Connection connection = newConnection()) {
            StateEnvelope envelope = readSnapshot(connection, flowType, flowId);
            List<PersistedEvent> tailEvents = readEvents(connection, flowType, flowId, envelope.snapshotVersion(), DEFAULT_REHYDRATE_LIMIT);
            return new RehydratedState(envelope, tailEvents);
        } catch (Exception ex) {
            throw new BackendUnavailableException("Failed to rehydrate flow " + flowType + "/" + flowId, ex);
        }
    }

    @Override
    public AppendResult appendEvents(String flowType,
                                     String flowId,
                                     long expectedVersion,
                                     List<PersistedEvent> events,
                                     String idempotencyKey) {
        List<PersistedEvent> safeEvents = events == null ? List.of() : events;
        try (Connection connection = newConnection()) {
            connection.setAutoCommit(false);
            long currentVersion = currentVersion(connection, flowType, flowId);
            if (currentVersion != expectedVersion) {
                throw new OptimisticConflictException(
                    "Version conflict for " + flowType + "/" + flowId + ": expected=" + expectedVersion + ", actual=" + currentVersion
                );
            }

            if (idempotencyKey != null && !idempotencyKey.isBlank() && isDuplicate(connection, flowType, flowId, idempotencyKey)) {
                connection.rollback();
                return new AppendResult(currentVersion, currentVersion, true);
            }

            long nextVersion = currentVersion;
            for (PersistedEvent event : safeEvents) {
                nextVersion++;
                insertEvent(connection, flowType, flowId, nextVersion, event);
            }

            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                insertIdempotency(connection, flowType, flowId, idempotencyKey, nextVersion);
            }

            connection.commit();
            return new AppendResult(currentVersion, nextVersion, false);
        } catch (OptimisticConflictException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BackendUnavailableException("Failed to append events for " + flowType + "/" + flowId, ex);
        }
    }

    @Override
    public void writeSnapshot(String flowType,
                              String flowId,
                              long version,
                              JsonNode snapshot,
                              Map<String, Object> metadata) {
        String sql = """
            INSERT INTO camel_flow_snapshot (flow_type, flow_id, version, snapshot_json, metadata_json, last_updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (flow_type, flow_id)
            DO UPDATE SET
              version = EXCLUDED.version,
              snapshot_json = EXCLUDED.snapshot_json,
              metadata_json = EXCLUDED.metadata_json,
              last_updated_at = EXCLUDED.last_updated_at
            """;

        try (Connection connection = newConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flowType);
            statement.setString(2, flowId);
            statement.setLong(3, version);
            statement.setString(4, mapper.writeValueAsString(snapshot == null ? NullNode.instance : snapshot));
            statement.setString(5, mapper.writeValueAsString(metadata == null ? Map.of() : metadata));
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
        } catch (Exception ex) {
            throw new BackendUnavailableException("Failed to write snapshot for " + flowType + "/" + flowId, ex);
        }
    }

    @Override
    public List<PersistedEvent> readEvents(String flowType, String flowId, long afterVersion, int limit) {
        try (Connection connection = newConnection()) {
            return readEvents(connection, flowType, flowId, afterVersion, limit);
        } catch (Exception ex) {
            throw new BackendUnavailableException("Failed to read events for " + flowType + "/" + flowId, ex);
        }
    }

    private void initializeSchema() {
        try (Connection connection = newConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE_SNAPSHOT);
            statement.executeUpdate(CREATE_EVENT);
            statement.executeUpdate(CREATE_IDEMPOTENCY);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_camel_flow_event_type_time ON camel_flow_event (event_type, occurred_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_camel_flow_event_flow ON camel_flow_event (flow_type, flow_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_camel_flow_snapshot_flow ON camel_flow_snapshot (flow_type, flow_id)");
        } catch (Exception ex) {
            throw new BackendUnavailableException("Failed to initialize PostgreSQL JDBC persistence schema", ex);
        }
    }

    private Connection newConnection() throws SQLException {
        if (jdbcUser.isBlank()) {
            return DriverManager.getConnection(jdbcUrl);
        }
        return DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
    }

    private StateEnvelope readSnapshot(Connection connection, String flowType, String flowId) throws Exception {
        String sql = """
            SELECT version, snapshot_json, metadata_json, last_updated_at
            FROM camel_flow_snapshot
            WHERE flow_type = ? AND flow_id = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flowType);
            statement.setString(2, flowId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new StateEnvelope(flowType, flowId, 0L, 0L, NullNode.instance, "", Map.of());
                }
                long version = resultSet.getLong("version");
                JsonNode snapshot = mapper.readTree(resultSet.getString("snapshot_json"));
                JsonNode metadataNode = mapper.readTree(resultSet.getString("metadata_json"));
                Map<String, Object> metadata = metadataNode == null || metadataNode.isNull()
                    ? Map.of()
                    : mapper.convertValue(metadataNode, new TypeReference<Map<String, Object>>() {
                    });
                String lastUpdatedAt = resultSet.getString("last_updated_at");
                return new StateEnvelope(flowType, flowId, version, version, snapshot, lastUpdatedAt, metadata);
            }
        }
    }

    private List<PersistedEvent> readEvents(Connection connection,
                                            String flowType,
                                            String flowId,
                                            long afterVersion,
                                            int limit) throws Exception {
        String sql = """
            SELECT event_id, sequence, event_type, payload_json, occurred_at, idempotency_key
            FROM camel_flow_event
            WHERE flow_type = ?
              AND flow_id = ?
              AND sequence > ?
            ORDER BY sequence ASC
            LIMIT ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flowType);
            statement.setString(2, flowId);
            statement.setLong(3, Math.max(afterVersion, 0L));
            statement.setInt(4, Math.max(limit, 1));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PersistedEvent> events = new ArrayList<>();
                while (resultSet.next()) {
                    events.add(new PersistedEvent(
                        resultSet.getString("event_id"),
                        flowType,
                        flowId,
                        resultSet.getLong("sequence"),
                        resultSet.getString("event_type"),
                        mapper.readTree(resultSet.getString("payload_json")),
                        resultSet.getString("occurred_at"),
                        resultSet.getString("idempotency_key")
                    ));
                }
                return events;
            }
        }
    }

    private long currentVersion(Connection connection, String flowType, String flowId) throws Exception {
        String sql = "SELECT COALESCE(MAX(sequence), 0) FROM camel_flow_event WHERE flow_type = ? AND flow_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flowType);
            statement.setString(2, flowId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private boolean isDuplicate(Connection connection, String flowType, String flowId, String idempotencyKey) throws Exception {
        String sql = """
            SELECT 1
            FROM camel_flow_idempotency
            WHERE flow_type = ?
              AND flow_id = ?
              AND idempotency_key = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flowType);
            statement.setString(2, flowId);
            statement.setString(3, idempotencyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void insertEvent(Connection connection,
                             String flowType,
                             String flowId,
                             long sequence,
                             PersistedEvent event) throws Exception {
        String sql = """
            INSERT INTO camel_flow_event (
              flow_type, flow_id, sequence, event_id, event_type, payload_json, occurred_at, idempotency_key
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flowType);
            statement.setString(2, flowId);
            statement.setLong(3, sequence);
            statement.setString(4, event.eventId());
            statement.setString(5, event.eventType());
            statement.setString(6, mapper.writeValueAsString(event.payload()));
            statement.setString(7, event.occurredAt());
            statement.setString(8, event.idempotencyKey());
            statement.executeUpdate();
        }
    }

    private void insertIdempotency(Connection connection,
                                   String flowType,
                                   String flowId,
                                   String idempotencyKey,
                                   long appliedVersion) throws Exception {
        String sql = """
            INSERT INTO camel_flow_idempotency (flow_type, flow_id, idempotency_key, applied_version)
            VALUES (?, ?, ?, ?)
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flowType);
            statement.setString(2, flowId);
            statement.setString(3, idempotencyKey);
            statement.setLong(4, appliedVersion);
            statement.executeUpdate();
        }
    }
}