package dev.vexelray.installer.core;

/**
 * What a project says about its installer. Plain mutable fields so the same class is filled by
 * Jackson (installer.json) and by Maven's plugin configuration. A platform is enabled by giving
 * it a section; today only {@code windows} exists.
 */
public final class InstallerConfig {
    /** Lowercase id; names the install record and the Apps entry. */
    public String id;
    /** Display name; also the default install folder name. */
    public String name;
    public String publisher;
    public String description;
    /** Default version; a request-level version wins. */
    public String version;

    /** owner/name of the GitHub repo the generated files are committed to. */
    public String repo;
    public String branch;
    /** Sub-folder of the repo holding the generated files, if not the root. */
    public String repoPath;
    /** owner/name that hosts the release downloads; defaults to {@link #repo}. */
    public String releasesRepo;
    /** Release tag pattern; {@code {version}} is substituted. */
    public String tagFormat;

    public PlatformConfig windows;

    public String branchOrDefault() { return blank(branch) ? "main" : branch; }
    public String tagFormatOrDefault() { return blank(tagFormat) ? "v{version}" : tagFormat; }
    public String publisherOrName() { return blank(publisher) ? name : publisher; }
    public String descriptionOrDefault() { return blank(description) ? name + "." : description; }
    public String releasesRepoOrRepo() { return blank(releasesRepo) ? repo : releasesRepo; }
    public String repoPathTrimmed() {
        return blank(repoPath) ? "" : repoPath.replaceAll("^[/\\\\]+|[/\\\\]+$", "");
    }

    static boolean blank(String s) { return s == null || s.isBlank(); }
}
