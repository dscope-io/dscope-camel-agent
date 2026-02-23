package io.dscope.camel.agent.persistence.dscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.AuditGranularity;
import io.dscope.camel.agent.model.DynamicRouteState;
import io.dscope.camel.agent.model.TaskState;
import io.dscope.camel.agent.model.TaskStatus;
import io.dscope.camel.persistence.core.AppendResult;
import io.dscope.camel.persistence.core.FlowStateStore;
import io.dscope.camel.persistence.core.PersistedEvent;
import io.dscope.camel.persistence.core.RehydratedState;
import io.dscope.camel.persistence.core.StateEnvelope;
import io.dscope.camel.persistence.core.exception.OptimisticConflictException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DscopePersistenceFacadeTest {

    @Test
    void shouldAppendAndReadConversationEvents() {
        TestFlowStateStore store = new TestFlowStateStore();
        DscopePersistenceFacade facade = new DscopePersistenceFacade(store, new ObjectMapper());
        AgentEvent event = new AgentEvent("c1", null, "user.message", new ObjectMapper().createObjectNode().put("text", "hello"), Instant.now());

        facade.appendEvent(event, "k1");

        List<AgentEvent> events = facade.loadConversation("c1", 10);
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals("user.message", events.getFirst().type());
    }

    @Test
    void shouldNotWriteAuditWhenGranularityIsNone() {
        TestFlowStateStore store = new TestFlowStateStore();
        DscopePersistenceFacade facade = new DscopePersistenceFacade(store, new ObjectMapper(), AuditGranularity.NONE);
        AgentEvent event = new AgentEvent("c-none", null, "assistant.message",
            new ObjectMapper().createObjectNode().put("text", "hello"), Instant.now());

        facade.appendEvent(event, "k-none");

        Assertions.assertTrue(store.eventsFor("agent.conversation", "c-none").isEmpty());
    }

    @Test
    void shouldWriteDebugWithPayloadAndMetadata() {
        TestFlowStateStore store = new TestFlowStateStore();
        DscopePersistenceFacade facade = new DscopePersistenceFacade(store, new ObjectMapper(), AuditGranularity.DEBUG);
        AgentEvent event = new AgentEvent("c-debug", "t1", "assistant.message",
            new ObjectMapper().createObjectNode().put("text", "hello"), Instant.now());

        facade.appendEvent(event, "k-debug");

        PersistedEvent stored = store.eventsFor("agent.conversation", "c-debug").getFirst();
        Assertions.assertTrue(stored.payload().has("payload"));
        Assertions.assertTrue(stored.payload().has("metadata"));
        Assertions.assertEquals("hello", stored.payload().path("payload").path("text").asText());
    }

    @Test
    void shouldWriteErrorDataOnlyForErrorEventsAtErrorGranularity() {
        TestFlowStateStore store = new TestFlowStateStore();
        DscopePersistenceFacade facade = new DscopePersistenceFacade(store, new ObjectMapper(), AuditGranularity.ERROR);
        AgentEvent normal = new AgentEvent("c-error", null, "tool.start",
            new ObjectMapper().createObjectNode().put("normal", true), Instant.now());
        AgentEvent error = new AgentEvent("c-error", null, "run.error",
            new ObjectMapper().createObjectNode().put("code", "E1"), Instant.now());

        facade.appendEvent(normal, "k-n");
        facade.appendEvent(error, "k-e");

        List<PersistedEvent> stored = store.eventsFor("agent.conversation", "c-error");
        Assertions.assertEquals(2, stored.size());
        Assertions.assertTrue(stored.get(0).payload().path("error").isNull());
        Assertions.assertEquals("E1", stored.get(1).payload().path("error").path("code").asText());
    }

    @Test
    void shouldClaimAndReleaseTaskLock() {
        TestFlowStateStore store = new TestFlowStateStore();
        DscopePersistenceFacade facade = new DscopePersistenceFacade(store, new ObjectMapper(), AuditGranularity.INFO);

        Assertions.assertTrue(facade.tryClaimTask("task-1", "node-a", 60));
        Assertions.assertTrue(facade.isTaskClaimedBy("task-1", "node-a"));
        Assertions.assertFalse(facade.tryClaimTask("task-1", "node-b", 60));

        facade.releaseTaskClaim("task-1", "node-a");
        Assertions.assertFalse(facade.isTaskClaimedBy("task-1", "node-a"));
        Assertions.assertTrue(facade.tryClaimTask("task-1", "node-b", 60));
    }

    @Test
    void shouldAllowClaimAfterLeaseExpires() {
        TestFlowStateStore store = new TestFlowStateStore();
        DscopePersistenceFacade facade = new DscopePersistenceFacade(store, new ObjectMapper(), AuditGranularity.INFO);

        Assertions.assertTrue(facade.tryClaimTask("task-2", "node-a", 1));
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Assertions.assertTrue(facade.tryClaimTask("task-2", "node-b", 60));
    }

    @Test
    void shouldPersistTaskSnapshot() {
        TestFlowStateStore store = new TestFlowStateStore();
        DscopePersistenceFacade facade = new DscopePersistenceFacade(store, new ObjectMapper());
        TaskState state = new TaskState("t1", "c1", TaskStatus.STARTED, "cp", null, 0, null);

        facade.saveTask(state);

        Assertions.assertTrue(facade.loadTask("t1").isPresent());
        Assertions.assertEquals(TaskStatus.STARTED, facade.loadTask("t1").orElseThrow().status());
    }

    @Test
    void shouldPersistDynamicRouteSnapshot() {
        TestFlowStateStore store = new TestFlowStateStore();
        DscopePersistenceFacade facade = new DscopePersistenceFacade(store, new ObjectMapper());
        DynamicRouteState route = new DynamicRouteState("r1", "http.request", "agent.route.r1", "CREATED", "c1",
            Instant.now(), Instant.now().plusSeconds(60));

        facade.saveDynamicRoute(route);
        var loaded = facade.loadDynamicRoute("r1");

        Assertions.assertTrue(loaded.isPresent());
        Assertions.assertEquals("http.request", loaded.orElseThrow().templateId());
    }

    private static final class TestFlowStateStore implements FlowStateStore {
        private final Map<String, List<PersistedEvent>> events = new HashMap<>();
        private final Map<String, com.fasterxml.jackson.databind.JsonNode> snapshots = new HashMap<>();
        private final Map<String, Long> snapshotVersions = new HashMap<>();

        @Override
        public RehydratedState rehydrate(String flowType, String flowId) {
            String key = flowType + ":" + flowId;
            var snapshot = snapshots.get(flowType + ":" + flowId);
            long eventVersion = events.getOrDefault(key, List.of()).size();
            long snapshotVersion = snapshotVersions.getOrDefault(key, 0L);
            long version = Math.max(eventVersion, snapshotVersion);
            StateEnvelope envelope = new StateEnvelope(flowType, flowId, version, snapshotVersion,
                snapshot, Instant.now().toString(), Map.of());
            return new RehydratedState(envelope, events.getOrDefault(flowType + ":" + flowId, List.of()));
        }

        @Override
        public AppendResult appendEvents(String flowType, String flowId, long expectedVersion, List<PersistedEvent> events, String idempotencyKey) {
            String key = flowType + ":" + flowId;
            long currentVersion = this.events.getOrDefault(key, List.of()).size();
            if (currentVersion != expectedVersion) {
                throw new OptimisticConflictException("Expected version " + expectedVersion + " but was " + currentVersion);
            }
            this.events.computeIfAbsent(key, k -> new ArrayList<>()).addAll(events);
            return new AppendResult(expectedVersion, expectedVersion + events.size(), false);
        }

        @Override
        public void writeSnapshot(String flowType, String flowId, long version, com.fasterxml.jackson.databind.JsonNode snapshotJson,
                                  Map<String, Object> metadata) {
            String key = flowType + ":" + flowId;
            snapshots.put(key, snapshotJson);
            snapshotVersions.put(key, version);
        }

        @Override
        public List<PersistedEvent> readEvents(String flowType, String flowId, long afterVersion, int limit) {
            return events.getOrDefault(flowType + ":" + flowId, List.of());
        }

        List<PersistedEvent> eventsFor(String flowType, String flowId) {
            return events.getOrDefault(flowType + ":" + flowId, List.of());
        }
    }
}
