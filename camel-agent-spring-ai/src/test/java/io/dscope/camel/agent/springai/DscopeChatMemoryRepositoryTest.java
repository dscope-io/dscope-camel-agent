package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.persistence.core.AppendResult;
import io.dscope.camel.persistence.core.FlowStateStore;
import io.dscope.camel.persistence.core.PersistedEvent;
import io.dscope.camel.persistence.core.RehydratedState;
import io.dscope.camel.persistence.core.StateEnvelope;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

class DscopeChatMemoryRepositoryTest {

    @Test
    void shouldSaveFindAndDeleteConversationMemory() {
        TestFlowStateStore store = new TestFlowStateStore();
        DscopeChatMemoryRepository repository = new DscopeChatMemoryRepository(store, new ObjectMapper());

        repository.saveAll("conv-1", List.of(UserMessage.builder().text("hello").build()));
        var messages = repository.findByConversationId("conv-1");

        Assertions.assertEquals(1, messages.size());
        Assertions.assertEquals("hello", messages.getFirst().getText());
        Assertions.assertTrue(repository.findConversationIds().contains("conv-1"));

        repository.deleteByConversationId("conv-1");
        Assertions.assertTrue(repository.findByConversationId("conv-1").isEmpty());
        Assertions.assertFalse(repository.findConversationIds().contains("conv-1"));
    }

    private static final class TestFlowStateStore implements FlowStateStore {

        private final Map<String, JsonNode> snapshots = new HashMap<>();
        private final Map<String, Long> versions = new HashMap<>();

        @Override
        public RehydratedState rehydrate(String flowType, String flowId) {
            String key = flowType + ":" + flowId;
            StateEnvelope envelope = new StateEnvelope(
                flowType,
                flowId,
                versions.getOrDefault(key, 0L),
                0L,
                snapshots.get(key),
                Instant.now().toString(),
                Map.of()
            );
            return new RehydratedState(envelope, List.of());
        }

        @Override
        public AppendResult appendEvents(String flowType, String flowId, long expectedVersion, List<PersistedEvent> events,
                                         String idempotencyKey) {
            return new AppendResult(expectedVersion, expectedVersion + events.size(), false);
        }

        @Override
        public void writeSnapshot(String flowType, String flowId, long version, JsonNode snapshotJson, Map<String, Object> metadata) {
            String key = flowType + ":" + flowId;
            snapshots.put(key, snapshotJson);
            versions.put(key, version);
        }

        @Override
        public List<PersistedEvent> readEvents(String flowType, String flowId, long afterVersion, int limit) {
            return List.of();
        }
    }
}
