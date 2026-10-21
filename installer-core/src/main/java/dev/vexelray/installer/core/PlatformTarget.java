package dev.vexelray.installer.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Everything specific to one operating system. The generator is platform-neutral: it stages and
 * hashes artifacts, merges the manifest and writes files; a target supplies the scripts, the
 * manifest section and the README text for its platform. Adding a platform means adding a
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

    /**
     * Template tokens of this platform's own, beside {@code NAME}, {@code CONFIG_JSON}, {@code INSTALL_URL} and
     * {@code BASE_URL}: for a script language that wants its configuration some other way than as JSON.
     */
    default Map<String, String> scriptVars(InstallerConfig config, PlatformConfig platform, String manifestUrl) { return Map.of(); }

    /** The format this platform's icons ship in: {@code ico} or {@code png}. */
    default String iconFormat() { return "ico"; }

    /** Refuses what this platform cannot install, once its binaries are staged; nothing has been written yet. */
    default void check(InstallerConfig config, PlatformConfig platform, List<ResolvedBinary> binaries) {}

    /** The manifest's {@code platforms.<key>} object for one version. */
    ObjectNode manifestEntry(InstallerConfig config, PlatformConfig platform, List<ResolvedBinary> binaries);

    /**
     * Files written beside {@code manifest.json}, made from the merged manifest (every version): file name to text.
     * For scripts that cannot read JSON.
     */
    default Map<String, String> companionFiles(InstallerConfig config, ObjectNode manifest) { return Map.of(); }

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
