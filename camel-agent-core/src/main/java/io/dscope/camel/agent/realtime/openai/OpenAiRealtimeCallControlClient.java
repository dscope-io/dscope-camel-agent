package io.dscope.camel.agent.realtime.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface OpenAiRealtimeCallControlClient {

    ObjectNode accept(String callId, ObjectNode payload) throws Exception;

    ObjectNode reject(String callId, ObjectNode payload) throws Exception;

    ObjectNode hangup(String callId) throws Exception;

    ObjectNode refer(String callId, ObjectNode payload) throws Exception;
}