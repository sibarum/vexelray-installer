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
        ResolvedIcon icon,
        java.util.Map<String, ResolvedIcon> typeIcons) {
    /** The staged, hashed {@code .ico} of a binary, or of a file type it opens. */
    public record ResolvedIcon(String file, String url, String sha256, long size, Path staged) {}

    /** The icons of {@code typeIcons}, each once, in the order the associations first name them. */
    public java.util.List<ResolvedIcon> distinctTypeIcons() {
        return typeIcons.values().stream().distinct().toList();
    }

    public boolean isJar(){ return "jar".equals(kind); }
    public boolean isArchive() { return "archive".equals(kind); }
    public boolean isFile() { return "file".equals(kind); }
}
