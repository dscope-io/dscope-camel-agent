package io.dscope.camel.agent.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import io.dscope.camel.a2a.A2AComponent;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class A2AToolClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        CorrelationRegistry.global().clear("conv-a2a");
    }

    @Test
    void sendsRemoteA2aMessageAndBindsCorrelation() throws Exception {
        CorrelationRegistry.global().bind("conv-a2a", "agui.sessionId", "session-1");
        CorrelationRegistry.global().bind("conv-a2a", "agui.runId", "run-1");
        CorrelationRegistry.global().bind("conv-a2a", "agui.threadId", "thread-1");
        FakeHttpClient httpClient = new FakeHttpClient(List.of("""
            {"jsonrpc":"2.0","result":{"task":{"taskId":"remote-task-1","status":{"state":"COMPLETED"},"latestMessage":{"parts":[{"text":"remote answer"}]},"metadata":{"camelAgent":{"linkedConversationId":"child-a2a-1","parentConversationId":"conv-a2a","rootConversationId":"conv-a2a"}}}},"id":"1"}
            """));
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();

        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            camelContext.addComponent("a2a", new A2AComponent());
            camelContext.start();

            A2AToolClient client = new A2AToolClient(
                camelContext,
                objectMapper,
                persistence,
                new A2AToolContext("support", "v1", "Support Agent", "1.0"),
                httpClient
            );

            ToolResult result = client.execute(
                "a2a:support-public?remoteUrl=http://example.test/a2a",
                new ToolSpec("remote.specialist", "Calls remote specialist", null,
                    "a2a:support-public?remoteUrl=http://example.test/a2a", null, null, new ToolPolicy(false, 0, 1000)),
                objectMapper.createObjectNode().put("text", "hello remote"),
                new ExecutionContext("conv-a2a", "task-local", "trace-1")
            );

            JsonNode request = objectMapper.readTree(httpClient.lastRequestBody());
            assertEquals("SendMessage", request.path("method").asText());
            assertEquals("support-public", request.path("params").path("metadata").path("agentId").asText());
            assertEquals("support", request.path("params").path("metadata").path("planName").asText());
            assertEquals("session-1", request.path("params").path("metadata").path("aguiSessionId").asText());
            assertEquals("thread-1", request.path("params").path("metadata").path("camelAgent").path("aguiThreadId").asText());
            assertEquals("remote answer", result.content());
            assertEquals("remote-task-1", CorrelationRegistry.global().resolve("conv-a2a", "a2a.remoteTaskId", ""));
            assertEquals("child-a2a-1", CorrelationRegistry.global().resolve("conv-a2a", "a2a.linkedConversationId", ""));
            assertTrue(persistence.loadConversation("conv-a2a", 10).stream().anyMatch(event -> "conversation.a2a.outbound.started".equals(event.type())));
            assertTrue(persistence.loadConversation("conv-a2a", 10).stream().anyMatch(event -> "conversation.a2a.outbound.completed".equals(event.type())));
        }
    }

    @Test
    void reusesCorrelatedRemoteTaskIdForFollowUpCalls() throws Exception {
        FakeHttpClient httpClient = new FakeHttpClient(List.of("""
            {"jsonrpc":"2.0","result":{"task":{"taskId":"remote-task-2","status":{"state":"COMPLETED"},"latestMessage":{"parts":[{"text":"created"}]}}},"id":"1"}
            """, """
            {"jsonrpc":"2.0","result":{"task":{"taskId":"remote-task-2","status":{"state":"COMPLETED"},"latestMessage":{"parts":[{"text":"fetched"}]}}},"id":"2"}
            """));

        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            camelContext.addComponent("a2a", new A2AComponent());
            camelContext.start();

            A2AToolClient client = new A2AToolClient(
                camelContext,
                objectMapper,
                new InMemoryPersistenceFacade(),
                new A2AToolContext("support", "v1", "Support Agent", "1.0"),
                httpClient
            );

            ToolSpec tool = new ToolSpec("remote.specialist", "Calls remote specialist", null,
                "a2a:support-public?remoteUrl=http://example.test/a2a", null, null, new ToolPolicy(false, 0, 1000));
            client.execute(tool.endpointUri(), tool, objectMapper.createObjectNode().put("text", "hello"), new ExecutionContext("conv-a2a", null, "trace-1"));
            client.execute(tool.endpointUri(), tool, objectMapper.createObjectNode().put("method", "GetTask"), new ExecutionContext("conv-a2a", null, "trace-2"));

            JsonNode request = objectMapper.readTree(httpClient.lastRequestBody());
            assertEquals("GetTask", request.path("method").asText());
            assertEquals("remote-task-2", request.path("params").path("taskId").asText());
        }
    }

    private static final class FakeHttpClient extends HttpClient {

        private final Queue<String> responses;
        private final Executor executor = Executors.newSingleThreadExecutor();
        private volatile String lastRequestBody = "";

        private FakeHttpClient(List<String> responses) {
            this.responses = new ConcurrentLinkedQueue<>(responses);
        }

        String lastRequestBody() {
            return lastRequestBody;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.of(executor);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
            lastRequestBody = request.bodyPublisher()
                .map(BodyPublisherReader::read)
                .orElse("");
            String responseBody = responses.remove();
            return (HttpResponse<T>) new FakeHttpResponse(request, responseBody);
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private record FakeHttpResponse(HttpRequest request, String body) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class BodyPublisherReader implements java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {

        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;

        static String read(HttpRequest.BodyPublisher publisher) {
            BodyPublisherReader reader = new BodyPublisherReader();
            publisher.subscribe(reader);
            return reader.bytes.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.nio.ByteBuffer buffer) {
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.write(chunk, 0, chunk.length);
        }

        @Override
        public void onError(Throwable throwable) {
            if (subscription != null) {
                subscription.cancel();
            }
            throw new IllegalStateException(throwable);
        }

        @Override
        public void onComplete() {
        }
    }
}
