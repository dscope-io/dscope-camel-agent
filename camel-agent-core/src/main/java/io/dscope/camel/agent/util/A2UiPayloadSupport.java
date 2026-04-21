package io.dscope.camel.agent.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.model.A2UiSpec;
import io.dscope.camel.agent.model.A2UiSurfaceSpec;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.runtime.ResolvedAgentPlan;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class A2UiPayloadSupport {

    public static final String PROTOCOL = "a2ui";
    public static final String VERSION = "v0.9";

    private static final String DEFAULT_LOCALE = "en";
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final ObjectMapper RESOURCE_MAPPER = new ObjectMapper();
    private static final Map<String, JsonNode> JSON_RESOURCE_CACHE = new ConcurrentHashMap<>();

    private A2UiPayloadSupport() {
    }

    public static String resolveLocale(String... candidates) {
        if (candidates == null) {
            return DEFAULT_LOCALE;
        }
        for (String candidate : candidates) {
            String normalized = normalizeLocale(candidate);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return DEFAULT_LOCALE;
    }

    public static List<String> supportedCatalogIds(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (raw instanceof JsonNode node) {
            if (node.isArray()) {
                node.forEach(item -> addSupportedCatalog(values, item == null ? null : item.asText("")));
            } else {
                addSupportedCatalog(values, node.asText(""));
            }
            return List.copyOf(values);
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                addSupportedCatalog(values, value == null ? null : String.valueOf(value));
            }
            return List.copyOf(values);
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                Object value = java.lang.reflect.Array.get(raw, i);
                addSupportedCatalog(values, value == null ? null : String.valueOf(value));
            }
            return List.copyOf(values);
        }
        addSupportedCatalog(values, String.valueOf(raw));
        return List.copyOf(values);
    }

    public static ObjectNode buildPayload(ObjectMapper mapper,
                                          AgentBlueprint blueprint,
                                          JsonNode model,
                                          ResolvedAgentPlan resolvedPlan,
                                          String locale,
                                          List<String> supportedCatalogIds) {
        if (mapper == null || blueprint == null || model == null || model.isNull()) {
            return null;
        }
        SelectedSurface selected = selectSurface(blueprint.a2ui(), model, supportedCatalogIds);
        if (selected == null) {
            return null;
        }
        JsonNode templateNode = loadJsonResource(selected.surface().surfaceResource());
        if (templateNode == null || templateNode.isNull()) {
            return null;
        }

        ObjectNode root = toPayloadRoot(mapper, templateNode);
        if (root == null) {
            return null;
        }

        String resolvedLocale = resolveLocale(locale);
        String surfaceId = resolveSurfaceId(selected.surface(), model);
        ObjectNode localeBundle = resolveLocaleBundle(mapper, selected.surface(), resolvedLocale);
        ObjectNode dataModel = createDataModel(mapper, model, localeBundle);
        Map<String, JsonNode> replacements = replacementValues(mapper, model, resolvedPlan, selected, resolvedLocale, surfaceId, localeBundle);

        ObjectNode substituted = (ObjectNode) replaceNode(mapper, root, replacements);
        substituted.put("protocol", PROTOCOL);
        substituted.put("version", firstNonBlank(text(substituted, "version"), VERSION));
        substituted.put("locale", resolvedLocale);
        substituted.put("surfaceId", surfaceId);
        substituted.put("catalogId", selected.catalogId());
        String widgetTemplate = firstNonBlank(selected.surface().widgetTemplate(), text(substituted, "template"));
        if (!widgetTemplate.isBlank()) {
            substituted.put("template", widgetTemplate);
        }
        if (resolvedPlan != null && !resolvedPlan.legacyMode()) {
            substituted.put("planName", nullToEmpty(resolvedPlan.planName()));
            substituted.put("planVersion", nullToEmpty(resolvedPlan.planVersion()));
        }

        ArrayNode envelopes = ensureEnvelopes(mapper, substituted);
        boolean dataModelApplied = false;
        for (JsonNode envelopeNode : envelopes) {
            if (!(envelopeNode instanceof ObjectNode envelope)) {
                continue;
            }
            envelope.put("version", firstNonBlank(text(envelope, "version"), VERSION));
            ObjectNode createSurface = object(envelope, "createSurface");
            if (createSurface != null) {
                createSurface.put("surfaceId", surfaceId);
                createSurface.put("catalogId", selected.catalogId());
                if (createSurface.path("theme").isObject()) {
                    ((ObjectNode) createSurface.path("theme")).put("locale", resolvedLocale);
                }
            }
            ObjectNode updateComponents = object(envelope, "updateComponents");
            if (updateComponents != null) {
                updateComponents.put("surfaceId", surfaceId);
            }
            ObjectNode updateDataModel = object(envelope, "updateDataModel");
            if (updateDataModel != null) {
                updateDataModel.put("surfaceId", surfaceId);
                updateDataModel.put("path", firstNonBlank(text(updateDataModel, "path"), "/"));
                updateDataModel.set("value", dataModel.deepCopy());
                dataModelApplied = true;
            }
        }

        if (!dataModelApplied) {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("version", VERSION);
            ObjectNode updateDataModel = mapper.createObjectNode();
            updateDataModel.put("surfaceId", surfaceId);
            updateDataModel.put("path", "/");
            updateDataModel.set("value", dataModel.deepCopy());
            envelope.set("updateDataModel", updateDataModel);
            envelopes.add(envelope);
        }
        substituted.set("envelopes", envelopes);
        return substituted;
    }

    public static ObjectNode buildWidget(ObjectMapper mapper,
                                         AgentBlueprint blueprint,
                                         JsonNode model,
                                         List<String> supportedCatalogIds) {
        if (mapper == null || blueprint == null || model == null || model.isNull()) {
            return null;
        }
        SelectedSurface selected = selectSurface(blueprint.a2ui(), model, supportedCatalogIds);
        if (selected == null || selected.surface().widgetTemplate() == null || selected.surface().widgetTemplate().isBlank()) {
            return null;
        }
        ObjectNode widget = mapper.createObjectNode();
        widget.put("template", selected.surface().widgetTemplate().trim());
        widget.put("catalogId", selected.catalogId());
        widget.set("data", model.deepCopy());
        return widget;
    }

    public static ObjectNode widgetDataFromA2Ui(ObjectMapper mapper, JsonNode payload) {
        if (mapper == null || payload == null || payload.isNull()) {
            return null;
        }
        JsonNode envelopes = payload.path("envelopes");
        if (!envelopes.isArray()) {
            return null;
        }
        for (JsonNode envelope : envelopes) {
            JsonNode dataModel = envelope.path("updateDataModel");
            if (!dataModel.isObject()) {
                continue;
            }
            JsonNode value = dataModel.path("value");
            if (value.isObject()) {
                return (ObjectNode) value.deepCopy();
            }
        }
        return null;
    }

    private static SelectedSurface selectSurface(A2UiSpec spec, JsonNode model, List<String> supportedCatalogIds) {
        if (spec == null || spec.surfaces() == null || spec.surfaces().isEmpty()) {
            return null;
        }
        List<String> supported = supportedCatalogIds == null ? List.of() : supportedCatalogIds;
        for (A2UiSurfaceSpec surface : spec.surfaces()) {
            if (surface == null || surface.surfaceResource() == null || surface.surfaceResource().isBlank()) {
                continue;
            }
            if (!matchesSurface(surface, model)) {
                continue;
            }
            String catalogId = resolveCatalogId(surface);
            if (catalogId.isBlank()) {
                continue;
            }
            if (!supported.isEmpty() && !supported.contains(catalogId)) {
                continue;
            }
            return new SelectedSurface(surface, catalogId);
        }
        return null;
    }

    private static boolean matchesSurface(A2UiSurfaceSpec surface, JsonNode model) {
        if (surface.matchFields() == null || surface.matchFields().isEmpty()) {
            return model.isObject();
        }
        for (String field : surface.matchFields()) {
            if (field == null || field.isBlank()) {
                continue;
            }
            JsonNode node = model.path(field.trim());
            if (node.isMissingNode() || node.isNull()) {
                return false;
            }
        }
        return true;
    }

    private static String resolveCatalogId(A2UiSurfaceSpec surface) {
        String direct = nullToEmpty(surface.catalogId()).trim();
        if (!direct.isBlank()) {
            return direct;
        }
        if (surface.catalogResource() == null || surface.catalogResource().isBlank()) {
            return "";
        }
        JsonNode catalog = loadJsonResource(surface.catalogResource());
        if (catalog == null || catalog.isNull()) {
            return "";
        }
        return firstNonBlank(text(catalog, "catalogId"), text(catalog, "$id"));
    }

    private static ObjectNode resolveLocaleBundle(ObjectMapper mapper, A2UiSurfaceSpec surface, String locale) {
        if (surface.localeResources() == null || surface.localeResources().isEmpty()) {
            return mapper.createObjectNode();
        }
        String resource = firstNonBlank(
            surface.localeResources().get(locale),
            surface.localeResources().get(languageOnly(locale)),
            surface.localeResources().get("default")
        );
        if (resource.isBlank()) {
            return mapper.createObjectNode();
        }
        JsonNode node = loadJsonResource(resource);
        if (node != null && node.isObject()) {
            return (ObjectNode) node.deepCopy();
        }
        return mapper.createObjectNode();
    }

    private static ObjectNode createDataModel(ObjectMapper mapper, JsonNode model, ObjectNode localeBundle) {
        ObjectNode data = mapper.createObjectNode();
        if (model != null && model.isObject()) {
            data.setAll((ObjectNode) model.deepCopy());
        } else if (model != null && !model.isNull()) {
            data.set("value", model.deepCopy());
        }
        if (localeBundle != null && !localeBundle.isEmpty()) {
            data.set("i18n", localeBundle.deepCopy());
        }
        return data;
    }

    private static Map<String, JsonNode> replacementValues(ObjectMapper mapper,
                                                           JsonNode model,
                                                           ResolvedAgentPlan resolvedPlan,
                                                           SelectedSurface selected,
                                                           String locale,
                                                           String surfaceId,
                                                           ObjectNode localeBundle) {
        Map<String, JsonNode> replacements = new LinkedHashMap<>();
        replacements.put("surfaceId", mapper.getNodeFactory().textNode(surfaceId));
        replacements.put("catalogId", mapper.getNodeFactory().textNode(selected.catalogId()));
        replacements.put("locale", mapper.getNodeFactory().textNode(locale));
        replacements.put("widgetTemplate", mapper.getNodeFactory().textNode(nullToEmpty(selected.surface().widgetTemplate())));
        replacements.put("agentDisplayName", mapper.getNodeFactory().textNode(displayName(resolvedPlan)));
        replacements.put("planName", mapper.getNodeFactory().textNode(resolvedPlan == null ? "" : nullToEmpty(resolvedPlan.planName())));
        replacements.put("planVersion", mapper.getNodeFactory().textNode(resolvedPlan == null ? "" : nullToEmpty(resolvedPlan.planVersion())));
        replacements.put("i18n", localeBundle == null ? mapper.createObjectNode() : localeBundle.deepCopy());
        if (model != null && model.isObject()) {
            model.properties().forEach(entry -> replacements.put(entry.getKey(), entry.getValue().deepCopy()));
        }
        return replacements;
    }

    private static ObjectNode toPayloadRoot(ObjectMapper mapper, JsonNode templateNode) {
        if (templateNode == null || templateNode.isNull()) {
            return null;
        }
        if (templateNode.isObject()) {
            return (ObjectNode) templateNode.deepCopy();
        }
        if (templateNode.isArray()) {
            ObjectNode root = mapper.createObjectNode();
            root.set("envelopes", templateNode.deepCopy());
            return root;
        }
        return null;
    }

    private static ArrayNode ensureEnvelopes(ObjectMapper mapper, ObjectNode root) {
        JsonNode envelopes = root.path("envelopes");
        if (envelopes.isArray()) {
            return (ArrayNode) envelopes;
        }
        ArrayNode created = mapper.createArrayNode();
        root.set("envelopes", created);
        return created;
    }

    private static JsonNode replaceNode(ObjectMapper mapper, JsonNode node, Map<String, JsonNode> replacements) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            node.properties().forEach(entry -> copy.set(entry.getKey(), replaceNode(mapper, entry.getValue(), replacements)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            node.forEach(item -> copy.add(replaceNode(mapper, item, replacements)));
            return copy;
        }
        if (!node.isTextual()) {
            return node.deepCopy();
        }
        String value = node.asText("");
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        if (matcher.matches()) {
            JsonNode replacement = replacements.get(matcher.group(1));
            return replacement == null ? node.deepCopy() : replacement.deepCopy();
        }
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            JsonNode replacement = replacements.get(matcher.group(1));
            String replacementText = replacement == null || replacement.isNull()
                ? ""
                : replacement.isValueNode() ? replacement.asText("") : replacement.toString();
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacementText));
        }
        matcher.appendTail(buffer);
        return mapper.getNodeFactory().textNode(buffer.toString());
    }

    private static JsonNode loadJsonResource(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        return JSON_RESOURCE_CACHE.computeIfAbsent(location.trim(), A2UiPayloadSupport::readJsonResource);
    }

    private static JsonNode readJsonResource(String location) {
        try (InputStream input = open(location)) {
            if (input == null) {
                return null;
            }
            return RESOURCE_MAPPER.readTree(input);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load A2UI JSON resource: " + location, e);
        }
    }

    private static InputStream open(String location) throws Exception {
        if (location.startsWith("classpath:")) {
            String path = location.substring("classpath:".length());
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            InputStream input = loader == null ? null : loader.getResourceAsStream(path);
            if (input != null) {
                return input;
            }
            loader = A2UiPayloadSupport.class.getClassLoader();
            return loader == null ? null : loader.getResourceAsStream(path);
        }
        if (location.startsWith("http://") || location.startsWith("https://")) {
            URLConnection connection = URI.create(location).toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            return connection.getInputStream();
        }
        if (location.startsWith("file:")) {
            return Files.newInputStream(Path.of(URI.create(location)));
        }
        return Files.newInputStream(Path.of(location));
    }

    private static ObjectNode object(ObjectNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        return node.isObject() ? (ObjectNode) node : null;
    }

    private static String displayName(ResolvedAgentPlan resolvedPlan) {
        if (resolvedPlan == null || resolvedPlan.legacyMode()) {
            return "Agent";
        }
        String name = nullToEmpty(resolvedPlan.planName());
        String version = nullToEmpty(resolvedPlan.planVersion());
        return version.isBlank() ? name : name + "/" + version;
    }

    private static String resolveSurfaceId(A2UiSurfaceSpec surface, JsonNode model) {
        String template = firstNonBlank(surface.surfaceIdTemplate(), surface.name(), surface.widgetTemplate(), "surface");
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = text(model, matcher.group(1));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return slug(buffer.toString());
    }

    private static String languageOnly(String locale) {
        if (locale == null || locale.isBlank()) {
            return "";
        }
        int separator = locale.indexOf('-');
        return separator < 0 ? locale : locale.substring(0, separator);
    }

    private static void addSupportedCatalog(List<String> values, String raw) {
        if (raw == null) {
            return;
        }
        for (String part : raw.split(",")) {
            String value = part == null ? "" : part.trim();
            if (!value.isBlank() && !values.contains(value)) {
                values.add(value);
            }
        }
    }

    private static String normalizeLocale(String value) {
        if (value == null) {
            return "";
        }
        String candidate = value.trim();
        if (candidate.isBlank()) {
            return "";
        }
        int comma = candidate.indexOf(',');
        if (comma >= 0) {
            candidate = candidate.substring(0, comma).trim();
        }
        int semicolon = candidate.indexOf(';');
        if (semicolon >= 0) {
            candidate = candidate.substring(0, semicolon).trim();
        }
        candidate = candidate.replace('_', '-');
        try {
            Locale locale = Locale.forLanguageTag(candidate);
            return locale.getLanguage().isBlank() ? "" : locale.toLanguageTag();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String slug(String value) {
        if (value == null) {
            return "surface";
        }
        String normalized = value.trim().replace('_', '-');
        String slug = normalized.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9-]+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "surface" : slug;
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record SelectedSurface(A2UiSurfaceSpec surface, String catalogId) {
    }
}
