package dev.vexelray.installer.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The documented example must stay loadable; unknown fields are errors, so typos are caught. */
class ExampleConfigTest {
    @Test
    void exampleConfigParses() throws IOException {
        InstallerConfig c = Json.read(Files.readString(Path.of("..", "installer.example.json")), InstallerConfig.class);
        assertEquals("myapp", c.id);
        assertEquals(2, c.windows.binaries.size());
        assertEquals(21, c.windows.binaries.get(1).javaMin);
    }

    @Test
    void unknownFieldsAreRejected() {
        assertThrows(IOException.class, () -> Json.read("{\"id\":\"x\",\"nmae\":\"typo\"}", InstallerConfig.class));
    }
}
