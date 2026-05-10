package io.dscope.camel.agent.realtime.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class OpenAiRealtimeCallProcessorTest {

    @Test
    void postsBrowserSdpAsRawApplicationSdp() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/realtime/calls", request -> {
            contentType.set(request.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestQuery.set(request.getRequestURI().getQuery());
            byte[] response = "answer-sdp".getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "application/sdp");
            request.sendResponseHeaders(200, response.length);
            request.getResponseBody().write(response);
            request.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/realtime/calls";
            OpenAiRealtimeCallProcessor processor = new OpenAiRealtimeCallProcessor(new ObjectMapper(), endpoint, "test-key", "gpt-realtime-2");
            Exchange exchange = new DefaultExchange(new DefaultCamelContext());
            String offerSdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n";
            exchange.getMessage().setBody("{\"sdp\":\"v=0\\r\\no=- 0 0 IN IP4 127.0.0.1\\r\\ns=-\\r\\nt=0 0\\r\\n\",\"session\":{\"type\":\"realtime\"}}");

            processor.process(exchange);

            assertEquals(200, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            assertEquals("application/sdp; charset=UTF-8", exchange.getMessage().getHeader(Exchange.CONTENT_TYPE));
            assertEquals("answer-sdp", exchange.getMessage().getBody(String.class));
            assertEquals("application/sdp", contentType.get());
            assertEquals(offerSdp, requestBody.get());
            assertEquals("model=gpt-realtime-2", requestQuery.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingApiKeyBeforeCallingOpenAi() throws Exception {
        OpenAiRealtimeCallProcessor processor = new OpenAiRealtimeCallProcessor(new ObjectMapper(), "http://127.0.0.1:1/v1/realtime/calls", "", "gpt-realtime-2");
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getMessage().setBody("{\"sdp\":\"offer-sdp\",\"session\":{}}");

        processor.process(exchange);

        assertEquals(500, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertTrue(exchange.getMessage().getBody(String.class).contains("OPENAI_API_KEY"));
    }
}
