package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.persistence.core.FlowStateStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

public class DscopeChatMemoryRepository implements ChatMemoryRepository {

    private static final String FLOW_CHAT_MEMORY = "agent.chat.memory";
    private static final String FLOW_CHAT_MEMORY_INDEX = "agent.chat.memory.index";
    private static final String INDEX_FLOW_ID = "all";

    private final FlowStateStore flowStateStore;
    private final SpringAiMessageSerde messageSerde;
    private final ObjectMapper objectMapper;

    public DscopeChatMemoryRepository(FlowStateStore flowStateStore, ObjectMapper objectMapper) {
        this.flowStateStore = flowStateStore;
        this.objectMapper = objectMapper;
        this.messageSerde = new SpringAiMessageSerde(objectMapper);
    }

    @Override
    public List<String> findConversationIds() {
        JsonNode snapshot = flowStateStore.rehydrate(FLOW_CHAT_MEMORY_INDEX, INDEX_FLOW_ID).envelope().snapshot();
        if (snapshot == null || snapshot.isMissingNode() || !snapshot.isArray()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        snapshot.forEach(node -> ids.add(node.asText()));
        return ids;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        JsonNode snapshot = flowStateStore.rehydrate(FLOW_CHAT_MEMORY, conversationId).envelope().snapshot();
        return messageSerde.deserialize(snapshot);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        JsonNode snapshot = messageSerde.serialize(messages);
        long nextVersion = nextVersion(FLOW_CHAT_MEMORY, conversationId);
        flowStateStore.writeSnapshot(FLOW_CHAT_MEMORY, conversationId, nextVersion, snapshot,
            Map.of("updatedAt", Instant.now().toString(), "conversationId", conversationId));
        upsertConversationIndex(conversationId);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        long nextVersion = nextVersion(FLOW_CHAT_MEMORY, conversationId);
        flowStateStore.writeSnapshot(FLOW_CHAT_MEMORY, conversationId, nextVersion, objectMapper.createArrayNode(),
            Map.of("updatedAt", Instant.now().toString(), "deleted", true));
        removeConversationIndex(conversationId);
    }

    private void upsertConversationIndex(String conversationId) {
        Set<String> ids = new LinkedHashSet<>(findConversationIds());
        ids.add(conversationId);
        writeIndex(ids);
    }

    private void removeConversationIndex(String conversationId) {
        Set<String> ids = new LinkedHashSet<>(findConversationIds());
        ids.remove(conversationId);
        writeIndex(ids);
    }

    private void writeIndex(Set<String> ids) {
        long version = nextVersion(FLOW_CHAT_MEMORY_INDEX, INDEX_FLOW_ID);
        JsonNode snapshot = objectMapper.valueToTree(ids);
        flowStateStore.writeSnapshot(FLOW_CHAT_MEMORY_INDEX, INDEX_FLOW_ID, version, snapshot,
            Map.of("updatedAt", Instant.now().toString()));
    }

    private long nextVersion(String flowType, String flowId) {
        var rehydrated = flowStateStore.rehydrate(flowType, flowId);
        return rehydrated.envelope() == null ? 1L : rehydrated.envelope().version() + 1L;
    }
}
