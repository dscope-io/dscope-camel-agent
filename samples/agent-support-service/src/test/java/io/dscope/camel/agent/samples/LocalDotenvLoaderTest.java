package io.dscope.camel.agent.samples;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDotenvLoaderTest {

    private static final String TEST_KEY = "CAMEL_AGENT_DOTENV_TEST_KEY";

    @AfterEach
    void clearProperty() {
        System.clearProperty(TEST_KEY);
    }

    @Test
    void shouldLoadLocalEnvironmentWithoutOverridingExistingValues(@TempDir Path tempDir) throws Exception {
        Path dotenv = tempDir.resolve(".env.local");
        Files.writeString(dotenv, "# local only\nexport " + TEST_KEY + "=\"from-file\"\nINVALID LINE\n");

        LocalDotenvLoader.load(dotenv);
        Assertions.assertEquals("from-file", System.getProperty(TEST_KEY));

        System.setProperty(TEST_KEY, "existing");
        LocalDotenvLoader.load(dotenv);
        Assertions.assertEquals("existing", System.getProperty(TEST_KEY));
    }
}
