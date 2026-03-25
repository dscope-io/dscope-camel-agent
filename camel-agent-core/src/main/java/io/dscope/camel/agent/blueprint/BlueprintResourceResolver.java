package io.dscope.camel.agent.blueprint;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;

import io.dscope.camel.agent.model.BlueprintResourceSpec;
import io.dscope.camel.agent.model.ResolvedBlueprintResource;

final class BlueprintResourceResolver {

    private static final CamelPdfExtractor PDF_EXTRACTOR = new CamelPdfExtractor();

    List<ResolvedBlueprintResource> resolve(List<BlueprintResourceSpec> specs) {
        List<ResolvedBlueprintResource> resolved = new ArrayList<>();
        if (specs == null || specs.isEmpty()) {
            return resolved;
        }
        for (BlueprintResourceSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            try {
                resolved.add(resolve(spec));
            } catch (RuntimeException e) {
                if (!spec.optional()) {
                    throw e;
                }
            }
        }
        return List.copyOf(resolved);
    }

    private ResolvedBlueprintResource resolve(BlueprintResourceSpec spec) {
        String uri = required(spec);
        ResourceContent content = read(uri, spec.maxBytes());
        String normalizedText = normalize(content.bytes(), content.text(), spec.format());
        return new ResolvedBlueprintResource(spec, uri, content.contentType(), content.sizeBytes(), normalizedText);
    }

    private String required(BlueprintResourceSpec spec) {
        if (spec.uri() == null || spec.uri().isBlank()) {
            throw new IllegalArgumentException("Blueprint resource is missing uri: " + spec.name());
        }
        return spec.uri().trim();
    }

    private ResourceContent read(String uri, long maxBytes) {
        try {
            if (uri.startsWith("classpath:")) {
                String path = uri.substring("classpath:".length());
                try (InputStream input = openClasspathResource(path)) {
                    if (input == null) {
                        throw new IllegalArgumentException("Blueprint resource not found on classpath: " + uri);
                    }
                    return fromStream(uri, input, "text/plain", maxBytes);
                }
            }
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                URLConnection connection = URI.create(uri).toURL().openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(30_000);
                if (connection instanceof HttpURLConnection http) {
                    http.setInstanceFollowRedirects(true);
                    int status = http.getResponseCode();
                    if (status < 200 || status >= 400) {
                        throw new IllegalStateException("HTTP " + status + " while resolving blueprint resource: " + uri);
                    }
                }
                try (InputStream input = connection.getInputStream()) {
                    return fromStream(uri, input, connection.getContentType(), maxBytes);
                }
            }
            String normalized = uri.startsWith("file:") ? uri.substring("file:".length()) : uri;
            Path path = Path.of(normalized);
            byte[] bytes = Files.readAllBytes(path);
            ensureWithinLimit(uri, bytes.length, maxBytes);
            return new ResourceContent(bytes.length, detectContentType(path), bytes, new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve blueprint resource: " + uri, e);
        }
    }

    private ResourceContent fromStream(String uri, InputStream input, String contentType, long maxBytes) throws IOException {
        byte[] bytes = input.readAllBytes();
        ensureWithinLimit(uri, bytes.length, maxBytes);
        return new ResourceContent(bytes.length, contentType, bytes, new String(bytes, StandardCharsets.UTF_8));
    }

    private void ensureWithinLimit(String uri, long sizeBytes, long maxBytes) {
        if (maxBytes > 0 && sizeBytes > maxBytes) {
            throw new IllegalArgumentException("Blueprint resource exceeds maxBytes: " + uri + " (" + sizeBytes + ">" + maxBytes + ")");
        }
    }

    private String detectContentType(Path path) {
        try {
            String detected = Files.probeContentType(path);
            if (detected != null && !detected.isBlank()) {
                return detected;
            }
        } catch (IOException ignored) {
        }
        return "text/plain";
    }

    private InputStream openClasspathResource(String path) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            InputStream input = contextLoader.getResourceAsStream(path);
            if (input != null) {
                return input;
            }
        }
        ClassLoader loader = BlueprintResourceResolver.class.getClassLoader();
        if (loader != null) {
            InputStream input = loader.getResourceAsStream(path);
            if (input != null) {
                return input;
            }
        }
        return BlueprintResourceResolver.class.getResourceAsStream(path.startsWith("/") ? path : "/" + path);
    }

    private String normalize(byte[] bytes, String text, String format) {
        if ((text == null || text.isBlank()) && (bytes == null || bytes.length == 0)) {
            return "";
        }
        String normalizedText = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (format == null || format.isBlank()) {
            return normalizedText;
        }
        String lower = format.trim().toLowerCase();
        if ("pdf".equals(lower)) {
            return PDF_EXTRACTOR.extract(bytes).replace("\r\n", "\n").replace('\r', '\n').trim();
        }
        if ("html".equals(lower)) {
            return normalizedText.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }
        if ("json".equals(lower)) {
            return normalizedText;
        }
        return normalizedText;
    }

    private record ResourceContent(long sizeBytes, String contentType, byte[] bytes, String text) {
    }

    private static final class CamelPdfExtractor {

        private final DefaultCamelContext camelContext;
        private final ProducerTemplate producerTemplate;

        private CamelPdfExtractor() {
            try {
                this.camelContext = new DefaultCamelContext();
                this.camelContext.start();
                this.producerTemplate = this.camelContext.createProducerTemplate();
                this.producerTemplate.start();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize Camel PDF extractor", e);
            }
        }

        private String extract(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return "";
            }
            try {
                String extracted = producerTemplate.requestBody("pdf:extractText", bytes, String.class);
                return extracted == null ? "" : extracted;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to extract text from PDF blueprint resource", e);
            }
        }
    }
}