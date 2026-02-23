package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

public final class ApplicationYamlLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private ApplicationYamlLoader() {
    }

    public static Properties loadFromClasspath(String resourcePath) throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = ApplicationYamlLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return properties;
            }
            Map<String, Object> root = YAML_MAPPER.readValue(stream, new TypeReference<Map<String, Object>>() { });
            if (root != null) {
                flatten(root, "", properties);
            }
        }
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> source, String prefix, Properties target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten((Map<String, Object>) nested, key, target);
            } else {
                target.setProperty(key, value == null ? "" : String.valueOf(value));
            }
        }
    }
}
