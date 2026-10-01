package dev.vexelray.installer.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Everything specific to one operating system. The generator is platform-neutral: it stages and
 * hashes artifacts, merges the manifest and writes files; a target supplies the scripts, the
 * manifest section and the README text for its platform. Adding macOS or Linux means adding a
 * target and its templates, nothing else.
 */
public interface PlatformTarget {
    /** Key in the manifest's {@code platforms} object and in {@link InstallerConfig}. */
    String key();

    /** This platform's section of the config, or null when the project doesn't target it. */
    PlatformConfig configOf(InstallerConfig config);

    /** Script file names this target generates; the first is the one-line installer. */
    List<String> scriptNames();

    /** Values baked into the scripts (JSON) so they know their manifest and where installs are recorded. */
    ObjectNode scriptConfig(InstallerConfig config, PlatformConfig platform, String manifestUrl);

    /** The manifest's {@code platforms.<key>} object for one version. */
    ObjectNode manifestEntry(InstallerConfig config, PlatformConfig platform, List<ResolvedBinary> binaries);

    /** The command users paste to install, given the URL of the first script. */
    String installCommand(String installerUrl);

    /** Markdown for this platform's part of the README. */
    String readmeSection(ReadmeContext context);

    record ReadmeContext(
            InstallerConfig config,
            PlatformConfig platform,
            List<ResolvedBinary> binaries,
            String version,
            String installerUrl,
            String manifestUrl) {}
}
