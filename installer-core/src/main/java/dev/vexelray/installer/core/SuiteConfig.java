package dev.vexelray.installer.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Several applications installed by one command. Each member stays an install of its own (its own
 * folder, record, Apps entry, updater and uninstaller), published from its own repo; the suite only
 * runs their installers in order. So a member is named by its {@code installer.json}, and nothing
 * about it is repeated here.
 */
public final class SuiteConfig {
    /** Lowercase id; names the suite's scripts, not an install (a suite has no install record). */
    public String id;
    public String name;
    public String description;

    /** owner/name of the GitHub repo the suite's generated files are committed to. */
    public String repo;
    public String branch;
    /** Sub-folder of the repo holding the generated files, if not the root. */
    public String repoPath;

    /** Installed in this order, so a member another one {@code requires} goes first. */
    public List<Member> members = new ArrayList<>();

    public static final class Member {
        /** Path to the member's installer.json, relative to the suite config. */
        public String config;
        /** Optional URL of a page about the application, linked from the suite's README. */
        public String page;
        /** Overrides the manifest URL derived from the member's own repo (testing, or other hosting). */
        public String manifestUrl;
        /**
         * Optional: install exactly this version rather than the manifest's {@code latest}, always (a snapshot is
         * rebuilt under the same version). For a preflight suite of snapshot builds.
         */
        public String version;
        /**
         * Optional: left out unless asked for. Its box in the window starts unticked, and an install without the
         * window skips it, unless it is installed already (then it is kept up to date) or {@code INSTALL_ONLY}
         * names it. For a demo, or an application not ready yet. Ignored when {@code version} pins it: a preflight
         * installs everything built together.
         */
        public Boolean optional;
    }

    public String branchOrDefault() { return InstallerConfig.blank(branch) ? "main" : branch; }
    public String repoPathTrimmed() {
        return InstallerConfig.blank(repoPath) ? "" : repoPath.replaceAll("^[/\\\\]+|[/\\\\]+$", "");
    }
}
