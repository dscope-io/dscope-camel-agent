package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AsyncEventPersistenceFacadeTest {

    @Test
    void shouldExposePendingEventsBeforeBackgroundFlushCompletes() throws Exception {
        BlockingPersistenceFacade delegate = new BlockingPersistenceFacade();
        AsyncEventPersistenceFacade facade = new AsyncEventPersistenceFacade(delegate, "test", 16, 10L, 1000L, 1000L);
        AgentEvent event = new AgentEvent(
            "conv-async-pending",
            null,
            "user.message",
            JsonNodeFactory.instance.textNode("hello"),
            Instant.now()
        );

        try {
            facade.appendEvent(event, "k1");

            List<AgentEvent> visible = facade.loadConversation("conv-async-pending", 10);
            Assertions.assertEquals(1, visible.size());
            Assertions.assertEquals("hello", visible.get(0).payload().asText());
            Assertions.assertTrue(facade.listConversationIds(10).contains("conv-async-pending"));
            Assertions.assertTrue(delegate.loadConversation("conv-async-pending", 10).isEmpty());

            delegate.release();
            Assertions.assertTrue(delegate.appended.await(2, TimeUnit.SECONDS));

            awaitPersisted(delegate, "conv-async-pending", 10);
        } finally {
            delegate.release();
            facade.close();
        }
    }

    @Test
    void shouldDelegateTaskOperationsSynchronously() {
        InMemoryPersistenceFacade delegate = new InMemoryPersistenceFacade();
        AsyncEventPersistenceFacade facade = new AsyncEventPersistenceFacade(delegate, "tasks", 16, 10L, 1000L, 1000L);

        try {
            facade.saveTask(new io.dscope.camel.agent.model.TaskState("task-1", "conv-1", io.dscope.camel.agent.model.TaskStatus.WAITING, "cp", null, 0, null));
            Assertions.assertTrue(facade.loadTask("task-1").isPresent());
        } finally {
            facade.close();
        }
    }

    private static void awaitPersisted(InMemoryPersistenceFacade delegate, String conversationId, int limit) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (!delegate.loadConversation(conversationId, limit).isEmpty()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        Assertions.fail("Timed out waiting for async audit persistence flush");
    }

    private static final class BlockingPersistenceFacade extends InMemoryPersistenceFacade {
        private final CountDownLatch gate = new CountDownLatch(1);
        private final CountDownLatch appended = new CountDownLatch(1);

        @Override
        public void appendEvent(AgentEvent event, String idempotencyKey) {
            try {
                if (!gate.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release blocking persistence facade");
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interruptedException);
            }
            super.appendEvent(event, idempotencyKey);
            appended.countDown();
        }

        private void release() {
            gate.countDown();
        }
    }
}