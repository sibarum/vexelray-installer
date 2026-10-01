package dev.vexelray.installer.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VersionsTest {
    @Test
    void ordersNumericPartsNotText() {
        assertTrue(Versions.compare("1.10.0", "1.9.0") > 0);
        assertEquals(0, Versions.compare("v1.2", "1.2.0"));
        assertTrue(Versions.compare("2", "1.99.99") > 0);
    }

    @Test
    void prereleaseSortsBeforeItsRelease() {
        assertTrue(Versions.compare("1.0.0-rc1", "1.0.0") < 0);
        assertTrue(Versions.compare("1.0.1-rc1", "1.0.0") > 0);
        assertTrue(Versions.isPre("0.1.0-SNAPSHOT"));
    }

    @Test
    void newestFirstPutsReleaseAheadOfPrerelease() {
        List<String> v = new ArrayList<>(List.of("1.0.0-rc1", "0.9.0", "1.0.0", "1.1.0-rc1"));
        v.sort(Versions.NEWEST_FIRST);
        assertEquals(List.of("1.1.0-rc1", "1.0.0", "1.0.0-rc1", "0.9.0"), v);
    }

    @Test
    void rejectsGarbage() {
        assertThrows(InstallerException.class, () -> Versions.parse("one.two"));
        assertThrows(InstallerException.class, () -> Versions.parse("1.2.3.4.5"));
    }
}
