package io.dscope.camel.agent.samples;

import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.main.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgUiStaticUiRouteIntegrationTest {

    @Test
    void shouldServeUiAndStaticIndexFromRoute() throws Exception {
        int port = randomPort();
        String previousTestPort = System.getProperty("agent.runtime.test-port");
        System.setProperty("agent.runtime.test-port", Integer.toString(port));

        Main main = new Main();
        main.bind("supportRealtimeEventProcessorCore", new OkJsonProcessor());
        main.bind("supportRealtimeTokenProcessor", new OkJsonProcessor());
        main.bind("supportRealtimeSessionInitProcessor", new OkJsonProcessor());

        try {
            AgentRuntimeBootstrap.bootstrap(main, "ag-ui-static-http-test.yaml");
            main.start();

            HttpResult ui = get(port, "/agui/ui");
            Assertions.assertEquals(200, ui.statusCode());
            Assertions.assertTrue(ui.body().contains("<title>Multi-Agent Copilot (Relay Primary)</title>"));

            HttpResult staticIndex = get(port, "/agui/ui/index.html");
            Assertions.assertEquals(200, staticIndex.statusCode());
            Assertions.assertTrue(staticIndex.body().contains("<title>Multi-Agent Copilot (Relay Primary)</title>"));
        } finally {
            try {
                main.stop();
            } catch (Exception ignored) {
            }
            if (previousTestPort == null) {
                System.clearProperty("agent.runtime.test-port");
            } else {
                System.setProperty("agent.runtime.test-port", previousTestPort);
            }
        }
    }

    private static HttpResult get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.body() == null ? "" : response.body());
    }

    private static int randomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record HttpResult(int statusCode, String body) {
    }

    private static final class OkJsonProcessor implements Processor {

        @Override
        public void process(Exchange exchange) {
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            exchange.getMessage().setBody("{\"ok\":true}");
        }
    }
}
