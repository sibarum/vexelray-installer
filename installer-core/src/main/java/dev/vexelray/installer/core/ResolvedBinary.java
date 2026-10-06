package dev.vexelray.installer.core;

import java.nio.file.Path;

/** A binary after the generator has located, staged and hashed it; what targets turn into manifest entries. */
public record ResolvedBinary(
        BinaryConfig config,
        String name,
        String file,
        String kind,
        String version,
        String url,
        String sha256,
        long size,
        Path staged,
        ResolvedIcon icon) {
    /** The staged, hashed {@code .ico} of a binary. */
    public record ResolvedIcon(String file, String url, String sha256, long size, Path staged) {}

    public boolean isJar() { return "jar".equals(kind); }
    public boolean isArchive() { return "archive".equals(kind); }
}
