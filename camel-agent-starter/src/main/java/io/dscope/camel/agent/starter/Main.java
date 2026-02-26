package io.dscope.camel.agent.starter;

import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;

final class Main {

    private Main() {
    }

    static void run(String[] args, String defaultApplicationConfig) throws Exception {
        ConfigSelection configSelection = selectApplicationConfig(args, defaultApplicationConfig);
        org.apache.camel.main.Main main = new org.apache.camel.main.Main();
        AgentRuntimeBootstrap.bootstrap(main, configSelection.applicationConfigPath());
        main.run(configSelection.runtimeArgs());
    }

    private static ConfigSelection selectApplicationConfig(String[] args, String defaultApplicationConfig) {
        if (args == null || args.length == 0) {
            return new ConfigSelection(defaultApplicationConfig, new String[0]);
        }

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (arg == null || arg.isBlank()) {
                continue;
            }

            if (arg.startsWith("--application-config=")) {
                String value = arg.substring("--application-config=".length()).trim();
                if (!value.isBlank()) {
                    validateYamlConfigPath(value);
                    String[] runtimeArgs = removeRange(args, index, index + 1);
                    return new ConfigSelection(value, runtimeArgs);
                }
                continue;
            }

            if (arg.equals("--application-config") && index + 1 < args.length) {
                String value = args[index + 1] == null ? "" : args[index + 1].trim();
                if (!value.isBlank()) {
                    validateYamlConfigPath(value);
                    String[] runtimeArgs = removeRange(args, index, index + 2);
                    return new ConfigSelection(value, runtimeArgs);
                }
                continue;
            }

            if (isYamlConfigPath(arg)) {
                String[] runtimeArgs = removeRange(args, index, index + 1);
                return new ConfigSelection(arg, runtimeArgs);
            }
        }

        return new ConfigSelection(defaultApplicationConfig, args);
    }

    private static boolean isYamlConfigPath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.endsWith(".yaml") || value.endsWith(".yml");
    }

    private static void validateYamlConfigPath(String value) {
        if (!isYamlConfigPath(value)) {
            throw new IllegalArgumentException("Unsupported application config format: " + value
                + " (supported: .yaml, .yml)");
        }
    }

    private static String[] removeRange(String[] values, int fromInclusive, int toExclusive) {
        int removeCount = Math.max(0, toExclusive - fromInclusive);
        int newLength = Math.max(0, values.length - removeCount);
        String[] result = new String[newLength];

        int outIndex = 0;
        for (int index = 0; index < values.length; index++) {
            if (index >= fromInclusive && index < toExclusive) {
                continue;
            }
            result[outIndex++] = values[index];
        }
        return result;
    }

    private record ConfigSelection(String applicationConfigPath, String[] runtimeArgs) {
    }
}