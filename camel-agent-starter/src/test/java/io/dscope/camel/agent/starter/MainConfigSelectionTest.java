package io.dscope.camel.agent.starter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class MainConfigSelectionTest {

    @Test
    void shouldSelectYamlFromExplicitArgument() {
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Object selection = select(new String[] {"--application-config=custom/application.yaml", "--trace"}, "application.yaml");

            Assertions.assertEquals("custom/application.yaml", configPath(selection));
            Assertions.assertArrayEquals(new String[] {"--trace"}, runtimeArgs(selection));
        });
    }

    @Test
    void shouldRejectXmlFromExplicitArgument() {
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> select(new String[] {"--application-config=application.xml"}, "application.yaml")
            );

            Assertions.assertTrue(exception.getMessage().contains("supported: .yaml, .yml"));
        });
    }

    @Test
    void shouldIgnoreXmlPositionalArgumentAndUseDefaultYaml() {
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Object selection = select(new String[] {"application.xml", "--trace"}, "application.yaml");

            Assertions.assertEquals("application.yaml", configPath(selection));
            Assertions.assertArrayEquals(new String[] {"application.xml", "--trace"}, runtimeArgs(selection));
        });
    }

    private static Object select(String[] args, String defaultConfig) throws Exception {
        Method selectMethod = Main.class.getDeclaredMethod("selectApplicationConfig", String[].class, String.class);
        selectMethod.setAccessible(true);
        try {
            return selectMethod.invoke(null, args, defaultConfig);
        } catch (InvocationTargetException exception) {
            if (exception.getTargetException() instanceof Exception target) {
                throw target;
            }
            throw exception;
        }
    }

    private static String configPath(Object selection) throws Exception {
        Method accessor = selection.getClass().getDeclaredMethod("applicationConfigPath");
        accessor.setAccessible(true);
        return (String) accessor.invoke(selection);
    }

    private static String[] runtimeArgs(Object selection) throws Exception {
        Method accessor = selection.getClass().getDeclaredMethod("runtimeArgs");
        accessor.setAccessible(true);
        return (String[]) accessor.invoke(selection);
    }
}
