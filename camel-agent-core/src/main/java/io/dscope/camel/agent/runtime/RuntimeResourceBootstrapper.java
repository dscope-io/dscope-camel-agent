package io.dscope.camel.agent.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RuntimeResourceBootstrapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeResourceBootstrapper.class);

    private static final String PROP_BLUEPRINT = "agent.blueprint";
    private static final String PROP_AGENTS_CONFIG = "agent.agents-config";
    private static final String PROP_ROUTES_PATTERN = "agent.runtime.routes-include-pattern";
    private static final String PROP_KAMELET_URLS = "agent.runtime.kamelets-include-pattern";
    private static final String PROP_KAMELET_URLS_ALIAS = "agent.runtime.kameletsIncludePattern";
    private static final String PROP_KAMELET_LOCATION = "camel.component.kamelet.location";

    private RuntimeResourceBootstrapper() {
    }

    static Properties resolve(Properties source) {
        Properties resolved = new Properties();
        resolved.putAll(source);

        BootstrapWorkspace workspace = new BootstrapWorkspace();
        resolveAgentsConfig(resolved, workspace);
        resolveBlueprint(resolved, workspace);
        resolveRoutes(resolved, workspace);
        resolveKamelets(resolved, workspace);

        workspace.logSummary();
        return resolved;
    }

    private static void resolveBlueprint(Properties properties, BootstrapWorkspace workspace) {
        String blueprint = trimToNull(properties.getProperty(PROP_BLUEPRINT));
        if (blueprint == null || !isHttpUrl(blueprint)) {
            return;
        }

        Path staged = workspace.downloadTo("agents", blueprint, ".md");
        String replacement = toFileUri(staged);
        properties.setProperty(PROP_BLUEPRINT, replacement);
        LOGGER.info("Runtime resource bootstrap: staged blueprint {} -> {}", blueprint, replacement);
    }

    private static void resolveAgentsConfig(Properties properties, BootstrapWorkspace workspace) {
        String config = trimToNull(properties.getProperty(PROP_AGENTS_CONFIG));
        if (config == null || !isHttpUrl(config)) {
            return;
        }
        Path staged = workspace.downloadTo("agents", config, ".yaml");
        String replacement = toFileUri(staged);
        properties.setProperty(PROP_AGENTS_CONFIG, replacement);
        LOGGER.info("Runtime resource bootstrap: staged agents-config {} -> {}", config, replacement);
        stageRemoteBlueprintsFromCatalog(properties, workspace, replacement);
    }

    private static void stageRemoteBlueprintsFromCatalog(Properties properties, BootstrapWorkspace workspace, String catalogLocation) {
        try {
            AgentPlanCatalog catalog = new AgentPlanCatalogLoader().load(catalogLocation);
            for (AgentPlanSpec plan : catalog.plans()) {
                if (plan == null || plan.versions() == null) {
                    continue;
                }
                for (AgentPlanVersionSpec version : plan.versions()) {
                    if (version == null || !isHttpUrl(version.blueprint())) {
                        continue;
                    }
                    Path staged = workspace.downloadTo("agents", version.blueprint(), ".md");
                    LOGGER.info("Runtime resource bootstrap: staged catalog blueprint {} -> {}", version.blueprint(), toFileUri(staged));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Runtime resource bootstrap: failed to stage catalog blueprints from {} ({})",
                catalogLocation,
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static void resolveRoutes(Properties properties, BootstrapWorkspace workspace) {
        String includePattern = trimToNull(properties.getProperty(PROP_ROUTES_PATTERN));
        if (includePattern == null) {
            return;
        }
        List<String> entries = splitCsv(includePattern);
        if (entries.isEmpty()) {
            return;
        }

        List<String> resolvedEntries = new ArrayList<>();
        boolean changed = false;
        for (String entry : entries) {
            if (isHttpUrl(entry)) {
                Path staged = workspace.downloadTo("routes", entry, inferRouteExtension(entry));
                resolvedEntries.add(toFileUri(staged));
                changed = true;
            } else {
                resolvedEntries.add(entry);
            }
        }

        if (changed) {
            String merged = String.join(",", resolvedEntries);
            properties.setProperty(PROP_ROUTES_PATTERN, merged);
            LOGGER.info("Runtime resource bootstrap: staged remote route resources from {}", includePattern);
        }
    }

    private static void resolveKamelets(Properties properties, BootstrapWorkspace workspace) {
        List<String> configured = splitCsv(firstNonBlank(
            properties.getProperty(PROP_KAMELET_URLS),
            properties.getProperty(PROP_KAMELET_URLS_ALIAS)
        ));
        if (configured.isEmpty()) {
            return;
        }

        Set<String> locations = new LinkedHashSet<>(splitCsv(properties.getProperty(PROP_KAMELET_LOCATION)));
        boolean changed = false;
        for (String location : configured) {
            if (isHttpUrl(location)) {
                workspace.downloadTo("kamelets", location, ".kamelet.yaml");
                changed = true;
            } else {
                locations.add(location);
                changed = true;
            }
        }

        Path stagedKamelets = workspace.resolveDirectory("kamelets");
        if (Files.exists(stagedKamelets) && isDirectoryNotEmpty(stagedKamelets)) {
            locations.add(toFileUri(stagedKamelets));
            changed = true;
        }

        if (changed && !locations.isEmpty()) {
            String merged = String.join(",", locations);
            properties.setProperty(PROP_KAMELET_LOCATION, merged);
            LOGGER.info("Runtime resource bootstrap: effective {}={}", PROP_KAMELET_LOCATION, merged);
        }
    }

    private static boolean isDirectoryNotEmpty(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    private static String inferRouteExtension(String sourceUrl) {
        String lower = sourceUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xml")) {
            return ".xml";
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return ".yaml";
        }
        return ".yaml";
    }

    private static List<String> splitCsv(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String token : value.split(",")) {
            String trimmed = trimToNull(token);
            if (trimmed != null) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    static String normalizeUrl(String source) {
        String trimmed = trimToNull(source);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.startsWith("https://github.com/") && trimmed.contains("/blob/")) {
            URI uri = URI.create(trimmed);
            String[] parts = uri.getPath().split("/");
            if (parts.length >= 5 && "blob".equals(parts[3])) {
                String owner = parts[1];
                String repo = parts[2];
                String branch = parts[4];
                StringBuilder path = new StringBuilder();
                for (int i = 5; i < parts.length; i++) {
                    if (!parts[i].isBlank()) {
                        if (path.length() > 0) {
                            path.append('/');
                        }
                        path.append(parts[i]);
                    }
                }
                String query = uri.getQuery();
                return "https://raw.githubusercontent.com/"
                    + owner + "/"
                    + repo + "/"
                    + branch + "/"
                    + path
                    + (query == null || query.isBlank() ? "" : "?" + query);
            }
        }
        return trimmed;
    }

    private static String toFileUri(Path path) {
        return path.toUri().toString();
    }

    private static final class BootstrapWorkspace {

        private final Path root;
        private int stagedFiles;

        private BootstrapWorkspace() {
            try {
                this.root = Files.createTempDirectory("camel-agent-bootstrap-");
                this.root.toFile().deleteOnExit();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create runtime bootstrap workspace", e);
            }
        }

        Path resolveDirectory(String kind) {
            Path directory = root.resolve(kind);
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create bootstrap directory: " + directory, e);
            }
            directory.toFile().deleteOnExit();
            return directory;
        }

        Path downloadTo(String kind, String sourceUrl, String fallbackExtension) {
            String normalized = normalizeUrl(sourceUrl);
            if (normalized == null) {
                throw new IllegalArgumentException("Invalid resource URL: " + sourceUrl);
            }
            Path directory = resolveDirectory(kind);
            String fileName = safeFileNameFromUrl(normalized, fallbackExtension);
            Path target = uniquify(directory.resolve(fileName));
            download(normalized, target);
            stagedFiles++;
            return target;
        }

        private static Path uniquify(Path candidate) {
            if (!Files.exists(candidate)) {
                return candidate;
            }
            String fileName = candidate.getFileName().toString();
            String base = fileName;
            String ext = "";
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) {
                base = fileName.substring(0, dot);
                ext = fileName.substring(dot);
            }
            int index = 1;
            Path parent = candidate.getParent();
            while (true) {
                Path next = parent.resolve(base + "-" + index + ext);
                if (!Files.exists(next)) {
                    return next;
                }
                index++;
            }
        }

        private static String safeFileNameFromUrl(String sourceUrl, String fallbackExtension) {
            URI uri = URI.create(sourceUrl);
            String path = uri.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return "resource" + fallbackExtension;
            }
            String candidate = path.substring(path.lastIndexOf('/') + 1).trim();
            if (candidate.isBlank()) {
                return "resource" + fallbackExtension;
            }
            if (!candidate.contains(".")) {
                return candidate + fallbackExtension;
            }
            return candidate;
        }

        private static void download(String sourceUrl, Path target) {
            try {
                URL url = URI.create(sourceUrl).toURL();
                URLConnection connection = url.openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(30_000);
                if (connection instanceof HttpURLConnection http) {
                    http.setInstanceFollowRedirects(true);
                    int status = http.getResponseCode();
                    if (status < 200 || status >= 400) {
                        throw new IllegalStateException("HTTP " + status + " while downloading resource: " + sourceUrl);
                    }
                }
                try (InputStream input = connection.getInputStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                target.toFile().deleteOnExit();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to download runtime resource: " + sourceUrl, e);
            }
        }

        void logSummary() {
            if (stagedFiles > 0) {
                LOGGER.info("Runtime resource bootstrap: staged {} remote resources under {}", stagedFiles, root);
            }
        }
    }
}
