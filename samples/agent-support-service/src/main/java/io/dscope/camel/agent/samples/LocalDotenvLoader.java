package io.dscope.camel.agent.samples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class LocalDotenvLoader {

    private static final String LOCAL_ENV_FILE = ".env.local";

    private LocalDotenvLoader() {
    }

    static Path loadFromWorkingDirectory() throws IOException {
        Path directory = Path.of("").toAbsolutePath().normalize();
        while (directory != null) {
            Path candidate = directory.resolve(LOCAL_ENV_FILE);
            if (Files.isRegularFile(candidate)) {
                load(candidate);
                return candidate;
            }
            directory = directory.getParent();
        }
        return null;
    }

    static void load(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        for (String line : lines) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            if (entry.startsWith("export ")) {
                entry = entry.substring("export ".length()).trim();
            }
            int separator = entry.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = entry.substring(0, separator).trim();
            String value = unquote(entry.substring(separator + 1).trim());
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                continue;
            }
            if (System.getenv(name) == null && System.getProperty(name) == null) {
                System.setProperty(name, value);
            }
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
