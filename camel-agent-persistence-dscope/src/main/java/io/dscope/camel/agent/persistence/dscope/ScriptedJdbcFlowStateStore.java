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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

final class ScriptedJdbcFlowStateStore implements FlowStateStore {

    private static final int DEFAULT_REHYDRATE_LIMIT = 100_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final String schemaResource;

    ScriptedJdbcFlowStateStore(String jdbcUrl, String jdbcUser, String jdbcPassword, String schemaResource) {
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser == null ? "" : jdbcUser;
        this.jdbcPassword = jdbcPassword == null ? "" : jdbcPassword;
        this.schemaResource = schemaResource;
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
        String snapshotJson;
        String metadataJson;
        try {
            snapshotJson = mapper.writeValueAsString(snapshot == null ? NullNode.instance : snapshot);
            metadataJson = mapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception ex) {
            throw new BackendUnavailableException("Failed to serialize snapshot payload for " + flowType + "/" + flowId, ex);
        }

        String timestamp = Instant.now().toString();
        String updateSql = """
            UPDATE camel_flow_snapshot
               SET version = ?, snapshot_json = ?, metadata_json = ?, last_updated_at = ?
             WHERE flow_type = ? AND flow_id = ?
            """;
        String insertSql = """
            INSERT INTO camel_flow_snapshot (flow_type, flow_id, version, snapshot_json, metadata_json, last_updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (Connection connection = newConnection()) {
            connection.setAutoCommit(false);
            int updated;
            try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                update.setLong(1, version);
                update.setString(2, snapshotJson);
                update.setString(3, metadataJson);
                update.setString(4, timestamp);
                update.setString(5, flowType);
                update.setString(6, flowId);
                updated = update.executeUpdate();
            }

            if (updated == 0) {
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                    insert.setString(1, flowType);
                    insert.setString(2, flowId);
                    insert.setLong(3, version);
                    insert.setString(4, snapshotJson);
                    insert.setString(5, metadataJson);
                    insert.setString(6, timestamp);
                    insert.executeUpdate();
                } catch (SQLException insertException) {
                    try (PreparedStatement retryUpdate = connection.prepareStatement(updateSql)) {
                        retryUpdate.setLong(1, version);
                        retryUpdate.setString(2, snapshotJson);
                        retryUpdate.setString(3, metadataJson);
                        retryUpdate.setString(4, timestamp);
                        retryUpdate.setString(5, flowType);
                        retryUpdate.setString(6, flowId);
                        int retried = retryUpdate.executeUpdate();
                        if (retried == 0) {
                            throw insertException;
                        }
                    }
                }
            }

            connection.commit();
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
        if (schemaResource == null || schemaResource.isBlank()) {
            throw new BackendUnavailableException("Missing JDBC schema resource for flow-state store");
        }
        try (Connection connection = newConnection(); Statement statement = connection.createStatement()) {
            for (String sql : loadSqlStatements(schemaResource)) {
                if (sql == null || sql.isBlank()) {
                    continue;
                }
                statement.executeUpdate(sql);
            }
        } catch (Exception ex) {
            throw new BackendUnavailableException("Failed to initialize JDBC persistence schema from resource: " + schemaResource, ex);
        }
    }

    private List<String> loadSqlStatements(String resource) throws Exception {
        String sqlText;
        try (InputStream inputStream = openResource(resource);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--")) {
                    continue;
                }
                builder.append(line).append('\n');
            }
            sqlText = builder.toString();
        }

        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        for (int index = 0; index < sqlText.length(); index++) {
            char ch = sqlText.charAt(index);
            if (ch == '\'') {
                boolean escaped = index + 1 < sqlText.length() && sqlText.charAt(index + 1) == '\'';
                if (!escaped) {
                    inSingleQuote = !inSingleQuote;
                }
                current.append(ch);
                continue;
            }
            if (ch == ';' && !inSingleQuote) {
                String statement = current.toString().trim();
                if (!statement.isBlank()) {
                    statements.add(statement);
                }
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }

        String tail = current.toString().trim();
        if (!tail.isBlank()) {
            statements.add(tail);
        }
        return statements;
    }

    private InputStream openResource(String resource) throws Exception {
        if (resource.startsWith("classpath:")) {
            String path = resource.substring("classpath:".length());
            InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(stripLeadingSlash(path));
            if (stream == null) {
                throw new IllegalStateException("Classpath resource not found: " + resource);
            }
            return stream;
        }
        if (resource.startsWith("file:")) {
            return Files.newInputStream(Path.of(URI.create(resource)));
        }

        InputStream classpathStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(stripLeadingSlash(resource));
        if (classpathStream != null) {
            return classpathStream;
        }

        Path path = Path.of(resource);
        if (Files.exists(path)) {
            return Files.newInputStream(path);
        }
        throw new IllegalStateException("Schema resource not found: " + resource);
    }

    private String stripLeadingSlash(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.startsWith("/") ? value.substring(1) : value;
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