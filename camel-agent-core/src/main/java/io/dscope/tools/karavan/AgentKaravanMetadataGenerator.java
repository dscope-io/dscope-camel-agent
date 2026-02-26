package io.dscope.tools.karavan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

public class AgentKaravanMetadataGenerator {

    private static final String COMPONENT_RESOURCE = "src/generated/resources/META-INF/io/dscope/camel/agent/component/agent.json";
    private static final String BASE_DIR = "src/main/resources/karavan/metadata";

    public static void main(String[] args) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path projectBaseDir = resolveProjectBaseDir();

        JsonNode source = readSourceMetadata(objectMapper, projectBaseDir.resolve(COMPONENT_RESOURCE));
        ObjectNode target = buildKaravanComponentMetadata(objectMapper, source);

        Path componentDir = projectBaseDir.resolve(BASE_DIR).resolve("component");
        Files.createDirectories(componentDir);
        File componentFile = componentDir.resolve("agent.json").toFile();
        objectMapper.writeValue(componentFile, target);
        System.out.println("Wrote " + projectBaseDir.relativize(componentFile.toPath()));

        Map<String, String> labels = buildLabels(source);
        Path labelsFile = projectBaseDir.resolve(BASE_DIR).resolve("model-labels.json");
        objectMapper.writeValue(labelsFile.toFile(), labels);
        System.out.println("Wrote " + projectBaseDir.relativize(labelsFile) + " (" + labels.size() + " labels)");

        System.out.println("\nAgent Karavan metadata generation complete.");
    }

    private static Path resolveProjectBaseDir() {
        String configured = System.getProperty("karavan.project.basedir", "");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static JsonNode readSourceMetadata(ObjectMapper objectMapper, Path componentPath) throws IOException {
        if (!Files.exists(componentPath)) {
            throw new IllegalStateException("Missing generated component metadata: " + componentPath);
        }
        return objectMapper.readTree(componentPath.toFile());
    }

    private static ObjectNode buildKaravanComponentMetadata(ObjectMapper objectMapper, JsonNode source) {
        ObjectNode root = objectMapper.createObjectNode();

        JsonNode sourceComponent = source.path("component");
        ObjectNode component = root.putObject("component");
        if (sourceComponent.isObject()) {
            component.setAll((ObjectNode) sourceComponent.deepCopy());
        }
        normalizeComponentLabels(objectMapper, component);

        JsonNode sourceProperties = source.path("properties");
        ObjectNode properties = root.putObject("properties");
        if (sourceProperties.isObject()) {
            properties.setAll((ObjectNode) sourceProperties.deepCopy());
        }

        return root;
    }

    private static void normalizeComponentLabels(ObjectMapper objectMapper, ObjectNode component) {
        JsonNode labelNode = component.get("label");
        if (labelNode == null || labelNode.isArray()) {
            return;
        }
        ArrayNode labels = objectMapper.createArrayNode();
        if (labelNode.isTextual()) {
            String raw = labelNode.asText("");
            for (String token : raw.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    labels.add(trimmed);
                }
            }
        }
        component.set("label", labels);
    }

    private static Map<String, String> buildLabels(JsonNode source) {
        Map<String, String> labels = new TreeMap<>();
        JsonNode component = source.path("component");
        String componentName = component.path("name").asText("agent");
        String title = component.path("title").asText("Agent");
        String description = component.path("description").asText("");

        labels.put("component." + componentName + ".title", title);
        if (!description.isBlank()) {
            labels.put("component." + componentName + ".description", description);
        }

        JsonNode properties = source.path("properties");
        if (properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> entries = properties.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                String name = entry.getKey();
                JsonNode value = entry.getValue();
                String propDescription = value.path("description").asText("");
                if (!propDescription.isBlank()) {
                    labels.put("property." + componentName + "." + name, propDescription);
                }
            }
        }

        return labels;
    }
}