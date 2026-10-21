package dev.vexelray.installer.core;

import java.util.ArrayList;
import java.util.List;

/** The per-platform part of the config: where to install and which binaries. */
public final class PlatformConfig {
    /** Default install folder; target-specific syntax (Windows expands %VARS% at install time). */
    public String installDir;
    /** Where install manifests are recorded so the uninstaller can find every install. */
    public String registryDir;
    public List<BinaryConfig> binaries = new ArrayList<>();
    /** Installs that must be present first; a binary's {@code runtime} is required implicitly. */
    public List<Requirement> requires = new ArrayList<>();
    /** What the computer must have; checked before anything large is downloaded. */
    public SystemRequirements system;
    /**
     * Linux only: the processor the binaries are built for, as {@code uname -m} says it ({@code x86_64}, {@code aarch64}).
     * Defaults to {@code x86_64} when the install has a native binary or an archive; jars and files run anywhere.
     */
    public String arch;
}
