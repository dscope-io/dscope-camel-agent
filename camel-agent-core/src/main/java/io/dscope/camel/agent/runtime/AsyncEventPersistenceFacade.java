package io.dscope.camel.agent.runtime;

import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.DynamicRouteState;
import io.dscope.camel.agent.model.TaskState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AsyncEventPersistenceFacade implements PersistenceFacade, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncEventPersistenceFacade.class);

    private final PersistenceFacade delegate;
    private final BlockingQueue<QueuedEvent> queue;
    private final Map<String, List<QueuedEvent>> pendingByConversation;
    private final AtomicLong sequence;
    private final AtomicBoolean accepting;
    private final long retryDelayMs;
    private final long shutdownTimeoutMs;
    private final String name;
    private final Thread workerThread;
    private final AtomicLong droppedEvents;
    private final AtomicLong enqueuedEvents;
    private final AtomicLong persistedEvents;
    private final AtomicLong retriedAttempts;
    private final long metricsLogIntervalMs;
    private final AtomicLong lastMetricsLogAt;

    public AsyncEventPersistenceFacade(PersistenceFacade delegate,
                                       String name,
                                       int queueCapacity,
                                       long retryDelayMs,
                                       long shutdownTimeoutMs,
                                       long metricsLogIntervalMs) {
        if (delegate == null) {
            throw new IllegalArgumentException("Delegate persistence facade is required");
        }
        this.delegate = delegate;
        this.queue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
        this.pendingByConversation = new java.util.concurrent.ConcurrentHashMap<>();
        this.sequence = new AtomicLong();
        this.accepting = new AtomicBoolean(true);
        this.retryDelayMs = Math.max(10L, retryDelayMs);
        this.shutdownTimeoutMs = Math.max(100L, shutdownTimeoutMs);
        this.name = (name == null || name.isBlank()) ? "audit" : name.trim();
        this.droppedEvents = new AtomicLong();
        this.enqueuedEvents = new AtomicLong();
        this.persistedEvents = new AtomicLong();
        this.retriedAttempts = new AtomicLong();
        this.metricsLogIntervalMs = Math.max(1000L, metricsLogIntervalMs);
        this.lastMetricsLogAt = new AtomicLong(System.currentTimeMillis());
        this.workerThread = Thread.ofPlatform()
            .name("agent-audit-async-" + this.name)
            .daemon(true)
            .start(this::runWorker);
    }

    @Override
    public void appendEvent(AgentEvent event, String idempotencyKey) {
        if (event == null) {
            return;
        }
        if (!accepting.get()) {
            delegate.appendEvent(event, idempotencyKey);
            return;
        }
        QueuedEvent queuedEvent = new QueuedEvent(event, idempotencyKey, sequence.incrementAndGet());
        addPending(queuedEvent);
        enqueuedEvents.incrementAndGet();
        if (!queue.offer(queuedEvent)) {
            removePending(queuedEvent);
            long dropped = droppedEvents.incrementAndGet();
            if (dropped == 1L || dropped % 100L == 0L) {
                LOGGER.warn("Async audit queue full; dropping event: name={}, conversationId={}, type={}, droppedEvents={}",
                    name,
                    event.conversationId(),
                    event.type(),
                    dropped);
            }
            logMetricsIfDue(false);
        }
    }

    @Override
    public List<AgentEvent> loadConversation(String conversationId, int limit) {
        if (conversationId == null || conversationId.isBlank() || limit <= 0) {
            return List.of();
        }
        List<QueuedEvent> pending = snapshotPending(conversationId);
        int delegateLimit = Math.max(limit, limit + pending.size() + 8);
        List<AgentEvent> persisted = delegate.loadConversation(conversationId, delegateLimit);

        Map<String, AgentEvent> merged = new LinkedHashMap<>();
        for (AgentEvent event : persisted) {
            merged.putIfAbsent(eventKey(event), event);
        }
        for (QueuedEvent queuedEvent : pending) {
            merged.put(eventKey(queuedEvent.event()), queuedEvent.event());
        }

        return merged.values().stream()
            .sorted((left, right) -> {
                int timestampComparison = eventTimestamp(left).compareTo(eventTimestamp(right));
                if (timestampComparison != 0) {
                    return timestampComparison;
                }
                return eventKey(left).compareTo(eventKey(right));
            })
            .skip(Math.max(0, merged.size() - limit))
            .toList();
    }

    @Override
    public List<String> listConversationIds(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> persisted = delegate.listConversationIds(Math.max(limit * 4, 50));
        List<String> pending = pendingByConversation.entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .sorted((left, right) -> latestPendingTimestamp(right.getValue()).compareTo(latestPendingTimestamp(left.getValue())))
            .map(Map.Entry::getKey)
            .toList();

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        pending.forEach(merged::add);
        persisted.forEach(merged::add);
        return merged.stream().limit(limit).toList();
    }

    @Override
    public void saveTask(TaskState taskState) {
        delegate.saveTask(taskState);
    }

    @Override
    public Optional<TaskState> loadTask(String taskId) {
        return delegate.loadTask(taskId);
    }

    @Override
    public void saveDynamicRoute(DynamicRouteState routeState) {
        delegate.saveDynamicRoute(routeState);
    }

    @Override
    public Optional<DynamicRouteState> loadDynamicRoute(String routeInstanceId) {
        return delegate.loadDynamicRoute(routeInstanceId);
    }

    @Override
    public List<DynamicRouteState> loadDynamicRoutes(int limit) {
        return delegate.loadDynamicRoutes(limit);
    }

    @Override
    public boolean tryClaimTask(String taskId, String ownerId, int leaseSeconds) {
        return delegate.tryClaimTask(taskId, ownerId, leaseSeconds);
    }

    @Override
    public void releaseTaskClaim(String taskId, String ownerId) {
        delegate.releaseTaskClaim(taskId, ownerId);
    }

    @Override
    public boolean isTaskClaimedBy(String taskId, String ownerId) {
        return delegate.isTaskClaimedBy(taskId, ownerId);
    }

    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        workerThread.interrupt();
        try {
            workerThread.join(shutdownTimeoutMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
        if (workerThread.isAlive()) {
            LOGGER.warn("Async audit worker did not drain before shutdown timeout: name={}, pendingEvents={}",
                name,
                pendingEventCount());
        }
        logMetrics(true);
    }

    private void runWorker() {
        while (accepting.get() || !queue.isEmpty()) {
            QueuedEvent queuedEvent = null;
            try {
                queuedEvent = queue.poll(250, TimeUnit.MILLISECONDS);
                if (queuedEvent == null) {
                    continue;
                }
                persistWithRetry(queuedEvent);
            } catch (InterruptedException interruptedException) {
                if (!accepting.get() && queue.isEmpty()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (RuntimeException failure) {
                if (queuedEvent != null) {
                    LOGGER.warn("Async audit worker failed unexpectedly: name={}, conversationId={}, type={}, error={}",
                        name,
                        queuedEvent.event().conversationId(),
                        queuedEvent.event().type(),
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
                }
            }
        }
    }

    private void persistWithRetry(QueuedEvent queuedEvent) throws InterruptedException {
        while (true) {
            try {
                delegate.appendEvent(queuedEvent.event(), queuedEvent.idempotencyKey());
                removePending(queuedEvent);
                persistedEvents.incrementAndGet();
                logMetricsIfDue(false);
                return;
            } catch (RuntimeException failure) {
                long attempts = queuedEvent.incrementAttempts();
                retriedAttempts.incrementAndGet();
                if (attempts == 1L || attempts % 10L == 0L) {
                    LOGGER.warn("Async audit persist retry scheduled: name={}, conversationId={}, type={}, attempts={}, error={}",
                        name,
                        queuedEvent.event().conversationId(),
                        queuedEvent.event().type(),
                        attempts,
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
                }
                logMetricsIfDue(false);
                TimeUnit.MILLISECONDS.sleep(retryDelayMs);
            }
        }
    }

    private void addPending(QueuedEvent queuedEvent) {
        pendingByConversation.compute(queuedEvent.event().conversationId(), (conversationId, existing) -> {
            List<QueuedEvent> events = existing == null ? new java.util.concurrent.CopyOnWriteArrayList<>() : existing;
            events.add(queuedEvent);
            return events;
        });
    }

    private void removePending(QueuedEvent queuedEvent) {
        pendingByConversation.computeIfPresent(queuedEvent.event().conversationId(), (conversationId, existing) -> {
            existing.remove(queuedEvent);
            return existing.isEmpty() ? null : existing;
        });
    }

    private List<QueuedEvent> snapshotPending(String conversationId) {
        List<QueuedEvent> pending = pendingByConversation.get(conversationId);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(pending);
    }

    private long pendingEventCount() {
        return pendingByConversation.values().stream().mapToLong(List::size).sum();
    }

    private void logMetricsIfDue(boolean force) {
        long now = System.currentTimeMillis();
        long last = lastMetricsLogAt.get();
        if (!force && now - last < metricsLogIntervalMs) {
            return;
        }
        if (!lastMetricsLogAt.compareAndSet(last, now) && !force) {
            return;
        }
        logMetrics(force);
    }

    private void logMetrics(boolean force) {
        long queued = queue.size();
        long pending = pendingEventCount();
        long dropped = droppedEvents.get();
        long enqueued = enqueuedEvents.get();
        long persisted = persistedEvents.get();
        long retries = retriedAttempts.get();
        if (!force && queued == 0L && pending == 0L && dropped == 0L && retries == 0L) {
            return;
        }
        LOGGER.info(
            "Async audit metrics: name={}, queueDepth={}, pendingEvents={}, enqueuedEvents={}, persistedEvents={}, retryAttempts={}, droppedEvents={}",
            name,
            queued,
            pending,
            enqueued,
            persisted,
            retries,
            dropped
        );
    }

    private static Instant latestPendingTimestamp(List<QueuedEvent> events) {
        if (events == null || events.isEmpty()) {
            return Instant.EPOCH;
        }
        return events.stream()
            .map(queuedEvent -> queuedEvent.event())
            .map(event -> eventTimestamp(event))
            .max(Instant::compareTo)
            .orElse(Instant.EPOCH);
    }

    private static Instant eventTimestamp(AgentEvent event) {
        if (event == null || event.timestamp() == null) {
            return Instant.EPOCH;
        }
        return event.timestamp();
    }

    private static String eventKey(AgentEvent event) {
        if (event == null) {
            return "";
        }
        return String.join("|",
            safe(event.conversationId()),
            safe(event.taskId()),
            safe(event.type()),
            safe(event.timestamp() == null ? "" : event.timestamp().toString()),
            safe(event.payload() == null ? "" : event.payload().toString()));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class QueuedEvent {
        private final AgentEvent event;
        private final String idempotencyKey;
        private final long sequence;
        private final AtomicLong attempts;

        private QueuedEvent(AgentEvent event, String idempotencyKey, long sequence) {
            this.event = event;
            this.idempotencyKey = idempotencyKey;
            this.sequence = sequence;
            this.attempts = new AtomicLong();
        }

        private AgentEvent event() {
            return event;
        }

        private String idempotencyKey() {
            return idempotencyKey;
        }

        @SuppressWarnings("unused")
        private long sequence() {
            return sequence;
        }

        private long incrementAttempts() {
            return attempts.incrementAndGet();
        }
    }
}