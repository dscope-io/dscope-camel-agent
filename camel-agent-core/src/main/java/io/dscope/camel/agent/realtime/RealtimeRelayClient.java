package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface RealtimeRelayClient {

    boolean isConnected(String conversationId);

    void connect(String conversationId, String endpointUri, String model, String apiKey) throws Exception;

    void connect(String conversationId, String endpointUri, String model, String apiKey, RealtimeReconnectPolicy reconnectPolicy)
        throws Exception;

    void sendEvent(String conversationId, String eventJson);

    ArrayNode pollEvents(String conversationId);

    void close(String conversationId);

    ObjectNode sessionState(String conversationId);
}
