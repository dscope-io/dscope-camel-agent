package io.dscope.camel.agent.agui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgUiStaticResourceProcessorTest {

    private final AgUiStaticResourceProcessor processor = new AgUiStaticResourceProcessor();

    @Test
    void shouldLoadClasspathResourceWhenFilesystemOverrideIsUnset() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Properties properties = new Properties();
            properties.setProperty("agui.ui.classpath-root", "agents/a2ui/locales");
            context.getPropertiesComponent().setInitialProperties(properties);

            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader(AgUiStaticResourceProcessor.RESOURCE_PATH_HEADER, "support-v1.en.json");

            processor.process(exchange);

            Assertions.assertEquals(200, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Assertions.assertEquals("application/json; charset=UTF-8", exchange.getMessage().getHeader(Exchange.CONTENT_TYPE));
            Assertions.assertInstanceOf(String.class, exchange.getMessage().getBody());
            Assertions.assertTrue(exchange.getMessage().getBody(String.class).contains("\"title\""));
        }
    }

    @Test
    void shouldPreferFilesystemOverrideAndPreserveBinaryBody(@TempDir Path tempDir) throws Exception {
        Path asset = tempDir.resolve("logo.png");
        byte[] expected = new byte[] { 1, 2, 3, 4 };
        Files.write(asset, expected);

        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Properties properties = new Properties();
            properties.setProperty("agui.ui.static-root", tempDir.toString());
            context.getPropertiesComponent().setInitialProperties(properties);

            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader(AgUiStaticResourceProcessor.RESOURCE_PATH_HEADER, "logo.png");

            processor.process(exchange);

            Assertions.assertEquals(200, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Assertions.assertEquals("image/png", exchange.getMessage().getHeader(Exchange.CONTENT_TYPE));
            Assertions.assertArrayEquals(expected, exchange.getMessage().getBody(byte[].class));
        }
    }

    @Test
    void shouldRejectTraversalAttempt() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader(AgUiStaticResourceProcessor.RESOURCE_PATH_HEADER, "../secret.txt");

            processor.process(exchange);

            Assertions.assertEquals(404, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Assertions.assertEquals("Not Found", exchange.getMessage().getBody(String.class));
        }
    }

    @Test
    void shouldServeTextFromFilesystemOverride(@TempDir Path tempDir) throws Exception {
        Path asset = tempDir.resolve("app.js");
        Files.writeString(asset, "console.log('agui');", StandardCharsets.UTF_8);

        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Properties properties = new Properties();
            properties.setProperty("agui.ui.static-root", tempDir.toString());
            context.getPropertiesComponent().setInitialProperties(properties);

            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader(AgUiStaticResourceProcessor.RESOURCE_PATH_HEADER, "app.js");

            processor.process(exchange);

            Assertions.assertEquals(200, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Assertions.assertEquals("application/javascript; charset=UTF-8", exchange.getMessage().getHeader(Exchange.CONTENT_TYPE));
            Assertions.assertEquals("console.log('agui');", exchange.getMessage().getBody(String.class));
        }
    }
}
