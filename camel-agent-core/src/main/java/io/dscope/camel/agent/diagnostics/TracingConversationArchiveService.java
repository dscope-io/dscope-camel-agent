package io.dscope.camel.agent.diagnostics;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.runtime.ConversationArchiveService;

public class TracingConversationArchiveService extends ConversationArchiveService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TracingConversationArchiveService.class);

    private final ConversationArchiveService delegate;
    private final boolean traceEnabled;

    public TracingConversationArchiveService(ConversationArchiveService delegate,
                                            boolean traceEnabled,
                                            ObjectMapper objectMapper) {
        super(null, objectMapper, delegate != null && delegate.enabled());
        this.delegate = delegate;
        this.traceEnabled = traceEnabled;
    }

    @Override
    public boolean enabled() {
        return delegate != null && delegate.enabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (delegate != null) {
            delegate.setEnabled(enabled);
        }
    }

    @Override
    public void appendAgUiTurn(String conversationId,
                               String userText,
                               String assistantText,
                               String sessionId,
                               String runId) {
        if (traceEnabled) {
            LOGGER.info("Archive append AGUI turn: conversationId={}, sessionId={}, runId={}, userChars={}, user={}, assistantChars={}, assistant={}",
                conversationId,
                sessionId,
                runId,
                userText == null ? 0 : userText.length(),
                TraceSupport.excerpt(userText),
                assistantText == null ? 0 : assistantText.length(),
                TraceSupport.excerpt(assistantText));
        }
        delegate.appendAgUiTurn(conversationId, userText, assistantText, sessionId, runId);
    }

    @Override
    public void appendRealtimeTranscriptObserved(String conversationId,
                                                 String direction,
                                                 String transcript,
                                                 String observedEventType) {
        if (traceEnabled) {
            LOGGER.info("Archive append realtime transcript: conversationId={}, direction={}, observedType={}, chars={}, transcript={}",
                conversationId,
                direction,
                observedEventType,
                transcript == null ? 0 : transcript.length(),
                TraceSupport.excerpt(transcript));
        }
        delegate.appendRealtimeTranscriptObserved(conversationId, direction, transcript, observedEventType);
    }

    @Override
    public void appendRealtimeTurn(String conversationId, String userText, String assistantText) {
        if (traceEnabled) {
            LOGGER.info("Archive append realtime turn: conversationId={}, userChars={}, user={}, assistantChars={}, assistant={}",
                conversationId,
                userText == null ? 0 : userText.length(),
                TraceSupport.excerpt(userText),
                assistantText == null ? 0 : assistantText.length(),
                TraceSupport.excerpt(assistantText));
        }
        delegate.appendRealtimeTurn(conversationId, userText, assistantText);
    }

    @Override
    public List<AgentEvent> loadConversationEvents(String conversationId, String sessionId, int limit) {
        List<AgentEvent> events = delegate.loadConversationEvents(conversationId, sessionId, limit);
        if (traceEnabled) {
            LOGGER.info("Archive load conversation events: conversationId={}, sessionId={}, limit={}, loaded={}, sample={}",
                conversationId,
                sessionId,
                limit,
                events.size(),
                TraceSupport.summarizeEvents(events));
        }
        return events;
    }
}