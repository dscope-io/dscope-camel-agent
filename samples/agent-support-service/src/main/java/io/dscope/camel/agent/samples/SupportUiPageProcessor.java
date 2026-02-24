package io.dscope.camel.agent.samples;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public final class SupportUiPageProcessor implements Processor {

    private static final String UI_RESOURCE = "frontend/index.html";
    private final String html;

    public SupportUiPageProcessor() {
        this.html = loadHtml();
    }

    @Override
    public void process(Exchange exchange) {
        exchange.getMessage().setBody(html);
    }

    private static String loadHtml() {
        ClassLoader classLoader = SupportUiPageProcessor.class.getClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(UI_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath resource: " + UI_RESOURCE);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load UI page from classpath resource: " + UI_RESOURCE, e);
        }
    }
}
