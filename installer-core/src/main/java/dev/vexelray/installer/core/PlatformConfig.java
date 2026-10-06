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
}
