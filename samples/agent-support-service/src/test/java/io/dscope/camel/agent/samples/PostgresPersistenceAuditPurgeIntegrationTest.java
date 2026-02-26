package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.DynamicRouteState;
import io.dscope.camel.agent.model.TaskState;
import io.dscope.camel.agent.model.TaskStatus;
import io.dscope.camel.agent.persistence.dscope.DscopePersistenceFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PostgresPersistenceAuditPurgeIntegrationTest {

    @Test
    void shouldPersistAuditToPostgresAndPurgeByClosedDateAndAgentType() throws Exception {
        String runtimeUrl = firstNonBlank(
            System.getProperty("it.postgres.runtime.url"),
            System.getenv("IT_POSTGRES_RUNTIME_URL")
        );
        String auditUrl = firstNonBlank(
            System.getProperty("it.postgres.audit.url"),
            System.getenv("IT_POSTGRES_AUDIT_URL")
        );
        String username = firstNonBlank(
            System.getProperty("it.postgres.user"),
            System.getenv("IT_POSTGRES_USER"),
            "agent"
        );
        String password = firstNonBlank(
            System.getProperty("it.postgres.password"),
            System.getenv("IT_POSTGRES_PASSWORD"),
            "agent"
        );
        String agentName = firstNonBlank(
            System.getProperty("it.postgres.agent.name"),
            System.getenv("IT_POSTGRES_AGENT_NAME"),
            "support-agent"
        );

        Assumptions.assumeTrue(runtimeUrl != null && !runtimeUrl.isBlank(), "Missing it.postgres.runtime.url");
        Assumptions.assumeTrue(auditUrl != null && !auditUrl.isBlank(), "Missing it.postgres.audit.url");

        String runtimeJdbcUrl = withJdbcCredentials(runtimeUrl, username, password);
        String auditJdbcUrl = withJdbcCredentials(auditUrl, username, password);

        ObjectMapper objectMapper = new ObjectMapper();
        Properties properties = new Properties();
        properties.setProperty("camel.persistence.enabled", "true");
        properties.setProperty("camel.persistence.backend", "jdbc");
        properties.setProperty("camel.persistence.jdbc.url", runtimeJdbcUrl);
        properties.setProperty("camel.persistence.jdbc.user", username);
        properties.setProperty("camel.persistence.jdbc.username", username);
        properties.setProperty("camel.persistence.jdbc.password", password);
        properties.setProperty("camel.persistence.jdbc.driver-class-name", "org.postgresql.Driver");
        properties.setProperty("agent.audit.granularity", "debug");
        properties.setProperty("agent.audit.persistence.backend", "jdbc");
        properties.setProperty("agent.audit.persistence.jdbc.url", auditJdbcUrl);
        properties.setProperty("agent.audit.persistence.jdbc.user", username);
        properties.setProperty("agent.audit.persistence.jdbc.password", password);

        PersistenceFacade persistenceFacade = DscopePersistenceFactory.create(properties, objectMapper);

        String conversationId = "pg-audit-" + UUID.randomUUID();
        String taskId = "task-" + UUID.randomUUID();
        String routeId = "route-" + UUID.randomUUID();

        ObjectNode userPayload = objectMapper.createObjectNode().put("text", "open support ticket");
        ObjectNode refreshedPayload = objectMapper.createObjectNode().put("agentName", agentName).put("agentVersion", "1.0.0");
        ObjectNode closedPayload = objectMapper.createObjectNode().put("reason", "integration-test");

        persistenceFacade.appendEvent(
            new AgentEvent(conversationId, null, "user.message", userPayload, Instant.now().minusSeconds(120)),
            UUID.randomUUID().toString()
        );
        persistenceFacade.appendEvent(
            new AgentEvent(conversationId, null, "agent.definition.refreshed", refreshedPayload, Instant.now().minusSeconds(60)),
            UUID.randomUUID().toString()
        );
        persistenceFacade.appendEvent(
            new AgentEvent(conversationId, null, "agent.instance.closed", closedPayload, Instant.now().minusSeconds(30)),
            UUID.randomUUID().toString()
        );

        persistenceFacade.saveTask(new TaskState(
            taskId,
            conversationId,
            TaskStatus.FINISHED,
            "done",
            null,
            0,
            "ok"
        ));
        persistenceFacade.saveDynamicRoute(new DynamicRouteState(
            routeId,
            "tpl",
            "route-1",
            "ACTIVE",
            conversationId,
            Instant.now().minusSeconds(20),
            Instant.now().plusSeconds(3600)
        ));

           try (Connection runtimeConn = open(runtimeJdbcUrl, username, password);
               Connection auditConn = open(auditJdbcUrl, username, password)) {

            long runtimeTaskBefore = countRuntimeTaskSnapshots(runtimeConn, conversationId);
            long runtimeRouteBefore = countRuntimeRouteSnapshots(runtimeConn, conversationId);
            long auditEventsBefore = countAuditConversationEvents(auditConn, conversationId);

            Assertions.assertTrue(runtimeTaskBefore > 0, "Expected runtime task snapshot in runtime DB");
            Assertions.assertTrue(runtimeRouteBefore > 0, "Expected runtime dynamic route snapshot in runtime DB");
            Assertions.assertTrue(auditEventsBefore > 0, "Expected audit events in audit DB");

            Instant purgeBefore = Instant.now().plusSeconds(60);
            List<String> targets = findPurgeTargets(auditConn, purgeBefore, agentName);
            Assertions.assertTrue(targets.contains(conversationId), "Expected conversation to match purge criteria");

            purgeAuditByConversationIds(auditConn, targets);
            purgeRuntimeByConversationIds(runtimeConn, targets);

            long runtimeTaskAfter = countRuntimeTaskSnapshots(runtimeConn, conversationId);
            long runtimeRouteAfter = countRuntimeRouteSnapshots(runtimeConn, conversationId);
            long auditEventsAfter = countAuditConversationEvents(auditConn, conversationId);

            Assertions.assertEquals(0L, runtimeTaskAfter, "Runtime task snapshots should be purged");
            Assertions.assertEquals(0L, runtimeRouteAfter, "Runtime dynamic routes should be purged");
            Assertions.assertEquals(0L, auditEventsAfter, "Audit conversation events should be purged");
        }
    }

    private static Connection open(String url, String username, String password) throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(url, username, password);
    }

    private static long countRuntimeTaskSnapshots(Connection connection, String conversationId) throws Exception {
        return countByConversationInSnapshot(connection, "agent.task", conversationId);
    }

    private static long countRuntimeRouteSnapshots(Connection connection, String conversationId) throws Exception {
        return countByConversationInSnapshot(connection, "agent.dynamicRoute", conversationId);
    }

    private static long countByConversationInSnapshot(Connection connection, String flowType, String conversationId) throws Exception {
        String sql = """
            SELECT COUNT(*)
            FROM camel_flow_snapshot
            WHERE flow_type = ?
              AND (snapshot_json::jsonb ->> 'conversationId') = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flowType);
            statement.setString(2, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long countAuditConversationEvents(Connection connection, String conversationId) throws Exception {
        String sql = """
            SELECT COUNT(*)
            FROM camel_flow_event
            WHERE flow_type = 'agent.conversation'
              AND flow_id = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static List<String> findPurgeTargets(Connection connection, Instant purgeBefore, String agentName) throws Exception {
        String sql = """
            SELECT DISTINCT e.flow_id AS conversation_id
            FROM camel_flow_event e
            WHERE e.flow_type = 'agent.conversation'
              AND e.flow_id IN (
                SELECT c.flow_id
                FROM camel_flow_event c
                WHERE c.flow_type = 'agent.conversation'
                  AND c.event_type = 'agent.instance.closed'
                  AND NULLIF(c.occurred_at, '')::timestamptz <= ?::timestamptz
              )
              AND e.flow_id IN (
                SELECT a.flow_id
                FROM camel_flow_event a
                WHERE a.flow_type = 'agent.conversation'
                  AND (
                    (a.payload_json::jsonb #>> '{payload,agentName}') = ?
                    OR (a.payload_json::jsonb ->> 'agentName') = ?
                  )
              )
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, purgeBefore.toString());
            statement.setString(2, agentName);
            statement.setString(3, agentName);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (resultSet.next()) {
                    ids.add(resultSet.getString("conversation_id"));
                }
                return ids;
            }
        }
    }

    private static void purgeAuditByConversationIds(Connection connection, List<String> conversationIds) throws Exception {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return;
        }
        for (String conversationId : conversationIds) {
            delete(connection, "DELETE FROM camel_flow_idempotency WHERE flow_type IN ('agent.conversation','agent.chat.memory') AND flow_id = ?", conversationId);
            delete(connection, "DELETE FROM camel_flow_event WHERE flow_type IN ('agent.conversation','agent.chat.memory') AND flow_id = ?", conversationId);
            delete(connection, "DELETE FROM camel_flow_snapshot WHERE flow_type IN ('agent.conversation','agent.chat.memory') AND flow_id = ?", conversationId);
            delete(connection, "DELETE FROM camel_flow_event WHERE flow_type = 'agent.conversation.index' AND (payload_json::jsonb ->> 'conversationId') = ?", conversationId);
        }
    }

    private static void purgeRuntimeByConversationIds(Connection connection, List<String> conversationIds) throws Exception {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return;
        }
        for (String conversationId : conversationIds) {
            List<String> taskIds = selectFlowIdsByConversation(connection, "agent.task", conversationId);
            List<String> routeIds = selectFlowIdsByConversation(connection, "agent.dynamicRoute", conversationId);

            for (String taskId : taskIds) {
                delete(connection, "DELETE FROM camel_flow_idempotency WHERE flow_type IN ('agent.task','agent.task.lock') AND flow_id = ?", taskId);
                delete(connection, "DELETE FROM camel_flow_event WHERE flow_type IN ('agent.task','agent.task.lock') AND flow_id = ?", taskId);
                delete(connection, "DELETE FROM camel_flow_snapshot WHERE flow_type IN ('agent.task','agent.task.lock') AND flow_id = ?", taskId);
            }

            for (String routeId : routeIds) {
                delete(connection, "DELETE FROM camel_flow_idempotency WHERE flow_type = 'agent.dynamicRoute' AND flow_id = ?", routeId);
                delete(connection, "DELETE FROM camel_flow_event WHERE flow_type = 'agent.dynamicRoute' AND flow_id = ?", routeId);
                delete(connection, "DELETE FROM camel_flow_snapshot WHERE flow_type = 'agent.dynamicRoute' AND flow_id = ?", routeId);
            }
        }
    }

    private static List<String> selectFlowIdsByConversation(Connection connection, String flowType, String conversationId) throws Exception {
        String sql = """
            SELECT flow_id
            FROM camel_flow_snapshot
            WHERE flow_type = ?
              AND (snapshot_json::jsonb ->> 'conversationId') = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flowType);
            statement.setString(2, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (resultSet.next()) {
                    ids.add(resultSet.getString("flow_id"));
                }
                return ids;
            }
        }
    }

    private static void delete(Connection connection, String sql, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String withJdbcCredentials(String url, String username, String password) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String normalized = url.trim();
        if (normalized.contains("user=") || normalized.contains("password=")) {
            return normalized;
        }
        String join = normalized.contains("?") ? "&" : "?";
        return normalized + join + "user=" + username + "&password=" + password;
    }
}