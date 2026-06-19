package io.dscope.camel.agent.agui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

/**
 * Serves AGUI static resources from an optional filesystem override first, then falls back to packaged classpath assets.
 */
public final class AgUiStaticResourceProcessor implements Processor {

    public static final String BEAN_NAME = "agUiStaticResourceProcessor";
    public static final String RESOURCE_PATH_HEADER = "AgUiStaticResourcePath";

    private static final String DEFAULT_CLASSPATH_ROOT = "frontend";
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
        Map.entry(".html", "text/html; charset=UTF-8"),
        Map.entry(".htm", "text/html; charset=UTF-8"),
        Map.entry(".css", "text/css; charset=UTF-8"),
        Map.entry(".js", "application/javascript; charset=UTF-8"),
        Map.entry(".mjs", "application/javascript; charset=UTF-8"),
        Map.entry(".json", "application/json; charset=UTF-8"),
        Map.entry(".map", "application/json; charset=UTF-8"),
        Map.entry(".webmanifest", "application/manifest+json; charset=UTF-8"),
        Map.entry(".txt", "text/plain; charset=UTF-8"),
        Map.entry(".md", "text/markdown; charset=UTF-8"),
        Map.entry(".csv", "text/csv; charset=UTF-8"),
        Map.entry(".xml", "application/xml; charset=UTF-8"),
        Map.entry(".yaml", "application/yaml; charset=UTF-8"),
        Map.entry(".yml", "application/yaml; charset=UTF-8"),
        Map.entry(".svg", "image/svg+xml; charset=UTF-8"),
        Map.entry(".png", "image/png"),
        Map.entry(".jpg", "image/jpeg"),
        Map.entry(".jpeg", "image/jpeg"),
        Map.entry(".gif", "image/gif"),
        Map.entry(".webp", "image/webp"),
        Map.entry(".avif", "image/avif"),
        Map.entry(".ico", "image/x-icon"),
        Map.entry(".wasm", "application/wasm"),
        Map.entry(".woff", "font/woff"),
        Map.entry(".woff2", "font/woff2"),
        Map.entry(".ttf", "font/ttf"),
        Map.entry(".otf", "font/otf"),
        Map.entry(".eot", "application/vnd.ms-fontobject"),
        Map.entry(".pdf", "application/pdf"),
        Map.entry(".webm", "video/webm"),
        Map.entry(".mp4", "video/mp4"),
        Map.entry(".mp3", "audio/mpeg"),
        Map.entry(".wav", "audio/wav"),
        Map.entry(".m4a", "audio/mp4"),
        Map.entry(".ogg", "audio/ogg")
    );

    @Override
    public void process(Exchange exchange) throws Exception {
        String requestedPath = exchange.getMessage().getHeader(RESOURCE_PATH_HEADER, String.class);
        String normalizedPath = normalizeRequestedPath(requestedPath);
        if (normalizedPath == null) {
            notFound(exchange);
            return;
        }

        byte[] body = readOverrideFile(exchange, normalizedPath);
        if (body == null) {
            body = readClasspathFile(exchange, normalizedPath);
        }
        if (body == null) {
            notFound(exchange);
            return;
        }

        String contentType = contentType(normalizedPath);
        Message message = exchange.getMessage();
        message.setBody(isTextContent(contentType) ? new String(body, StandardCharsets.UTF_8) : body);
        message.setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        message.setHeader(Exchange.CONTENT_TYPE, contentType);
        message.setHeader("Content-Length", body.length);
    }

    private byte[] readOverrideFile(Exchange exchange, String normalizedPath) throws IOException {
        String configuredRoot = firstNonBlank(
            property(exchange, "agui.ui.static-root"),
            property(exchange, "agui.ui.staticRoot")
        );
        if (configuredRoot == null) {
            return null;
        }

        try {
            Path root = Path.of(configuredRoot).normalize();
            Path candidate = root.resolve(normalizedPath).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                return null;
            }
            return Files.readAllBytes(candidate);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private byte[] readClasspathFile(Exchange exchange, String normalizedPath) throws IOException {
        String classpathRoot = firstNonBlank(
            property(exchange, "agui.ui.classpath-root"),
            property(exchange, "agui.ui.classpathRoot"),
            property(exchange, "agui.ui.static-classpath-root"),
            property(exchange, "agui.ui.staticClasspathRoot"),
            DEFAULT_CLASSPATH_ROOT
        );
        String resourcePath = joinClasspathPath(classpathRoot, normalizedPath);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = AgUiStaticResourceProcessor.class.getClassLoader();
        }
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    private static String normalizeRequestedPath(String requestedPath) {
        if (requestedPath == null) {
            return null;
        }
        String value = requestedPath.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isBlank() || value.contains("..")) {
            return null;
        }
        return value;
    }

    private static String joinClasspathPath(String root, String relativePath) {
        String normalizedRoot = root == null ? "" : root.trim().replace('\\', '/');
        while (normalizedRoot.startsWith("/")) {
            normalizedRoot = normalizedRoot.substring(1);
        }
        while (normalizedRoot.endsWith("/")) {
            normalizedRoot = normalizedRoot.substring(0, normalizedRoot.length() - 1);
        }
        return normalizedRoot.isEmpty() ? relativePath : normalizedRoot + "/" + relativePath;
    }

    private static String property(Exchange exchange, String key) {
        try {
            String value = exchange.getContext().resolvePropertyPlaceholders("{{" + key + "}}");
            return value != null && value.contains("{{") ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String contentType(String path) {
        String lowercase = path.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : CONTENT_TYPES.entrySet()) {
            if (lowercase.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "application/octet-stream";
    }

    private static boolean isTextContent(String contentType) {
        return contentType.startsWith("text/")
            || contentType.startsWith("application/json")
            || contentType.startsWith("application/javascript")
            || contentType.startsWith("application/xml")
            || contentType.startsWith("application/yaml")
            || contentType.startsWith("application/manifest+json")
            || contentType.startsWith("image/svg+xml");
    }

    private static void notFound(Exchange exchange) {
        Message message = exchange.getMessage();
        message.setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
        message.setHeader(Exchange.CONTENT_TYPE, "text/plain; charset=UTF-8");
        message.setBody("Not Found");
    }
}
