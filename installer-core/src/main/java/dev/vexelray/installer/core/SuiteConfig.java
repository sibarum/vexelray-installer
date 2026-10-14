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

    /** The landing page written by {@code --site}; not needed for the install script and README. */
    public Site site;

    public static final class Member {
        /** Path to the member's installer.json, relative to the suite config. */
        public String config;
        /** Optional URL of a page about the application, linked from the install page. */
        public String page;
        /** Overrides the manifest URL derived from the member's own repo (testing, or other hosting). */
        public String manifestUrl;
        /** Optional screenshot for the landing page, relative to the suite config. */
        public String image;
        /** What the screenshot shows, for readers who can't see it. */
        public String imageAlt;
    }

    /**
     * A landing page served from its own address (GitHub Pages), with a copy of the install script beside it
     * so the command is short. The members' manifests stay where their own runs published them.
     */
    public static final class Site {
        /** Where the page is served, for example {@code https://owner.github.io}; the command uses it. */
        public String url;
        /** One line under the name; the suite's {@code description} goes under the command. */
        public String tagline;
        /** A short label above the name, such as the release and its status. */
        public String badge;
        /** What a computer needs to run the members, said once under the command. */
        public String requirements;
        /** Where to report problems; the page asks for feedback when this is set. */
        public String feedbackUrl;
        /** CSS colour of the copy button and links. */
        public String accentColor;
        /** Links in the page header, in order. */
        public List<Link> links = new ArrayList<>();
    }

    public static final class Link {
        public String label;
        public String url;
    }

    public String branchOrDefault() { return InstallerConfig.blank(branch) ? "main" : branch; }
    public String repoPathTrimmed() {
        return InstallerConfig.blank(repoPath) ? "" : repoPath.replaceAll("^[/\\\\]+|[/\\\\]+$", "");
    }
}
