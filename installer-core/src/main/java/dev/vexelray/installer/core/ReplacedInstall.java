package dev.vexelray.installer.core;

/**
 * An install this application used to be published as, under another {@code id}: the same program, renamed. When the
 * installer finds it on a machine, it carries its choices over (shortcuts, PATH, file types), installs this one beside
 * it, and then removes it with this install's own uninstaller, so the person ends up with one copy under the new name.
 */
public final class ReplacedInstall {
    /** The id it was installed under. */
    public String id;
    /**
     * Where its generated files were committed, in the same {@code repo}, if they were: its updaters, already installed,
     * keep asking there. The generator writes a copy of this application's manifests and install scripts to that folder
     * as well, so an old updater finds this application's latest version and runs its installer, which then does the
     * above. Without it, an old install moves over only when the person runs the new install command.
     */
    public String repoPath;
}
