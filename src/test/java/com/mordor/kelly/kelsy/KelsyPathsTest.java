package com.mordor.kelly.kelsy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KelsyPathsTest {

    @Test
    void defaultsUnderKellyKelsy() {
        Path home = Path.of("/tmp/home");
        KelsyPaths paths = KelsyPaths.forHome(home);
        assertEquals(home.resolve(".kelly/kelsy/config.json"), paths.config());
        assertEquals(home.resolve(".kelly/kelsy/workspace"), paths.workspace());
        assertEquals(home.resolve(".kelsy/config.json"), paths.legacyConfig());
    }

    @Test
    void withWorkspaceKeepsConfig() {
        Path home = Path.of("/tmp/home");
        KelsyPaths paths = KelsyPaths.forHome(home).withWorkspace(Path.of("/data/ws"));
        assertEquals(home.resolve(".kelly/kelsy/config.json"), paths.config());
        assertEquals(Path.of("/data/ws"), paths.workspace());
        assertEquals(home.resolve(".kelsy/config.json"), paths.legacyConfig());
    }
}
