package dev.vexelray.installer.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a suite's one-line installer and its install page. The suite's script installs nothing itself:
 * it fetches each member's published manifest, downloads that member's own {@code install.ps1}, checks it
 * against the SHA-256 the manifest records, and runs it. So every member is installed exactly as its own
 * one-liner would install it, and what a member's install does is decided in one place, its own config.
 *
 * <p>{@link #generate} writes the script and a Markdown page for the repo the script is committed to.
 *
 * <p>Needs no artifacts and no network: it reads the members' {@code installer.json} files, which is
 * enough to know their names and where their manifests are published.
 */
public final class SuiteGenerator {

    /** One member, read from its installer.json. */
    private record Resolved(InstallerConfig config, SuiteConfig.Member member, String manifestUrl, String version) {
        /** Left out unless asked for; a pinned member never is, since a preflight installs everything built together. */
        boolean optional() { return Boolean.TRUE.equals(member.optional) && version == null; }
        boolean windows() { return config.windows != null; }
        boolean linux() { return config.linux != null; }
    }

    /**
     * @param baseDir member {@code config} paths are resolved against this (the suite config's folder)
     * @param baseUrl overrides the public URL of {@code outDir}; null for raw.githubusercontent.com
     * @return the command users paste
     */
    public String generate(SuiteConfig s, Path baseDir, Path outDir, String baseUrl) {
        try {
            return run(s, baseDir, outDir, baseUrl);
        } catch (IOException e) {
            throw new InstallerException("I/O error: " + e.getMessage(), e);
        }
    }

    private String run(SuiteConfig s, Path baseDir, Path outDir, String baseUrlOverride) throws IOException {
        List<Resolved> members = resolve(s, baseDir);
        List<Resolved> win = members.stream().filter(Resolved::windows).toList();
        List<Resolved> lin = members.stream().filter(Resolved::linux).toList();
        String baseUrl = InstallerGenerator.baseUrl(s.repo, s.branchOrDefault(), s.repoPathTrimmed(), baseUrlOverride);
        String installUrl = baseUrl + "/install.ps1", shUrl = baseUrl + "/install.sh";
        if (!win.isEmpty()) writeScript(s, win, outDir, installUrl);
        if (!lin.isEmpty()) writeShScript(s, members, outDir, shUrl);

        boolean pinned = members.stream().anyMatch(r -> r.version != null);
        String rerun = pinned
                ? "Running the same line again installs these versions again, so a rebuilt snapshot replaces the one installed."
                : "Running the same line again updates each of them to its latest version.";
        Map<String, String> vars = new HashMap<>();
        vars.put("NAME", s.name);
        vars.put("DESCRIPTION", InstallerConfig.blank(s.description) ? "" : s.description.trim() + "\n");
        vars.put("PLANNED", lin.isEmpty() ? "macOS and Linux installers are planned." : "A macOS installer is planned.");
        vars.put("WINDOWS", "");
        vars.put("LINUX", "");
        if (!win.isEmpty()) {
            Resolved first = win.get(0);
            Map<String, String> w = new HashMap<>();
            w.put("INSTALL_COMMAND", "irm " + installUrl + " | iex");
            w.put("INSTALL_URL", installUrl);
            w.put("MEMBER_TABLE", memberTable(win, pinned, r -> WindowsTarget.installDir(r.config, r.config.windows)));
            w.put("RERUN", rerun);
            w.put("EXAMPLE_IDS", exampleIds(win));
            w.put("OPTIONAL", optionalNote(win));
            w.put("PS_EXAMPLE_DIR", WindowsTarget.powershellPath(WindowsTarget.installDir(first.config, first.config.windows)));
            w.put("EXAMPLE_ID", first.config.id);
            w.put("REGISTRY_DIR", WindowsTarget.registryDir(first.config.windows));
            vars.put("WINDOWS", Templates.render("suite/README.windows.md", w));
        }
        if (!lin.isEmpty()) {
            Resolved first = lin.get(0);
            List<String> windowsOnly = members.stream().filter(r -> !r.linux()).map(r -> r.config.name).toList();
            Map<String, String> l = new HashMap<>();
            l.put("SH_COMMAND", "curl -fsSL " + shUrl + " | sh");
            l.put("SH_URL", shUrl);
            l.put("MEMBER_TABLE", memberTable(lin, pinned, r -> LinuxTarget.displayPath(LinuxTarget.installDir(r.config, r.config.linux))));
            l.put("RERUN", rerun);
            l.put("WINDOWS_ONLY", windowsOnly.isEmpty() ? "" : " " + and(windowsOnly)
                    + (windowsOnly.size() == 1 ? " has" : " have") + " no Linux version yet, so " + (windowsOnly.size() == 1 ? "it is" : "they are") + " left out.");
            l.put("EXAMPLE_IDS", exampleIds(lin));
            l.put("OPTIONAL", optionalNoteLinux(lin));
            l.put("SH_EXAMPLE_DIR", LinuxTarget.shellPath(LinuxTarget.installDir(first.config, first.config.linux)));
            l.put("REGISTRY_DIR", LinuxTarget.displayPath(LinuxTarget.registryDir(first.config.linux)));
            vars.put("LINUX", Templates.render("suite/README.linux.md", l));
        }
        String readme = Templates.render("suite/README.md", vars);
        Path readmePath = outDir.resolve("README.md");
        if (Files.exists(readmePath)
                && !Files.readString(readmePath, StandardCharsets.UTF_8).contains(InstallerGenerator.README_MARKER)) {
            readmePath = outDir.resolve("INSTALL.md");
        }
        InstallerGenerator.writeUtf8(readmePath, readme);
        return win.isEmpty() ? "curl -fsSL " + shUrl + " | sh" : "irm " + installUrl + " | iex";
    }

    /** The members, what each is and where it goes on one platform; a version column when any is pinned. */
    private static String memberTable(List<Resolved> members, boolean pinned, java.util.function.Function<Resolved, String> dir) {
        List<String> table = new ArrayList<>();
        table.add("| Application | What it is | Id |" + (pinned ? " Version |" : "") + " Installed to |");
        table.add("| --- | --- | --- |" + (pinned ? " --- |" : "") + " --- |");
        for (Resolved r : members) {
            String name = InstallerConfig.blank(r.member.page) ? r.config.name : "[" + r.config.name + "](" + r.member.page + ")";
            String version = !pinned ? "" : (r.version == null ? "latest" : "`" + r.version + "`") + " | ";
            table.add("| " + name + " | " + r.config.descriptionOrDefault() + " | `" + r.config.id + "` | " + version
                    + "`" + dir.apply(r) + "` |");
        }
        return String.join("\n", table);
    }


    private List<Resolved> resolve(SuiteConfig s, Path baseDir) throws IOException {
        if (s == null) throw new InstallerException("no suite config");
        if (!InstallerGenerator.validId(s.id))
            throw new InstallerException("'id' is required: lowercase letters, digits, . _ -");
        if (InstallerConfig.blank(s.name)) throw new InstallerException("'name' is required");
        if (s.members == null || s.members.isEmpty()) throw new InstallerException("a suite needs at least one member");

        List<Resolved> members = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (SuiteConfig.Member m : s.members) {
            if (m == null || InstallerConfig.blank(m.config))
                throw new InstallerException("every member needs a 'config' (the path to its installer.json)");
            Path p = Path.of(m.config);
            if (!p.isAbsolute()) p = baseDir.resolve(p);
            if (!Files.isRegularFile(p)) throw new InstallerException("member config not found: " + p);
            InstallerConfig c = Json.read(Files.readString(p, StandardCharsets.UTF_8), InstallerConfig.class);
            if (!InstallerGenerator.validId(c.id)) throw new InstallerException(p + " has no valid 'id'");
            if (InstallerConfig.blank(c.name)) throw new InstallerException(p + " has no 'name'");
            if (c.windows == null && c.linux == null) throw new InstallerException(c.id + " has neither a 'windows' nor a 'linux' section");
            if (c.name.matches("(?s).*[\\t\\r\\n].*")) throw new InstallerException(p + " has a 'name' with a tab or a line break");
            if (!ids.add(c.id)) throw new InstallerException("the suite lists '" + c.id + "' twice");
            String manifestUrl = !InstallerConfig.blank(m.manifestUrl) ? m.manifestUrl
                    : InstallerGenerator.baseUrl(c.repo, c.branchOrDefault(), c.repoPathTrimmed(), null) + "/manifest.json";
            String version = null;
            if (!InstallerConfig.blank(m.version)) {
                version = m.version.trim().replaceFirst("^[vV]", "");
                // it goes into the script inside a quoted string, and into INSTALL_VERSION
                if (!version.matches("[0-9A-Za-z][0-9A-Za-z.+-]*"))
                    throw new InstallerException("'" + m.version + "' (the version for " + c.id + ") is not a version");
            }
            members.add(new Resolved(c, m, manifestUrl, version));
        }
        return members;
    }

    private static void writeScript(SuiteConfig s, List<Resolved> members, Path outDir, String installUrl) throws IOException {
        ObjectNode cfg = Json.object();
        cfg.put("name", s.name);
        ArrayNode list = cfg.putArray("members");
        for (Resolved r : members) {
            ObjectNode n = list.addObject();
            n.put("id", r.config.id);
            n.put("name", r.config.name);
            n.put("manifestUrl", r.manifestUrl);
            n.put("registryDir", WindowsTarget.registryDir(r.config.windows));
            if (r.version != null) n.put("version", r.version);
            if (r.optional()) n.put("optional", true);
        }
        String idList = String.join(", ", members.stream().map(r -> r.config.id).toList());

        Files.createDirectories(outDir);
        String script = Templates.render("suite/install.ps1", Map.of(
                "NAME", s.name,
                "INSTALL_URL", installUrl,
                "IDS", idList,
                "CONFIG_JSON", Json.compact(cfg).replace("'", "''")));
        InstallerGenerator.writeAscii(outDir.resolve("install.ps1"), script);
    }

    /**
     * The suite's {@code install.sh}: the same plan for Linux, which runs each member's own {@code install.sh}. Every
     * member is listed, those without a Linux section too, so the script can say why it leaves one out.
     */
    private static void writeShScript(SuiteConfig s, List<Resolved> members, Path outDir, String installUrl) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Resolved r : members) {
            b.append("    vi_member ").append(LinuxTarget.shQuote(r.config.id))
                    .append(' ').append(LinuxTarget.shQuote(r.config.name))
                    .append(' ').append(LinuxTarget.shQuote(LinuxTarget.manifestUrl(r.manifestUrl)))
                    .append(' ').append(LinuxTarget.shQuote(r.version == null ? "-" : r.version))
                    .append(' ').append(r.optional() ? 1 : 0)
                    .append(' ').append(r.linux() ? 1 : 0)
                    .append(' ').append(LinuxTarget.shQuote(r.linux() ? LinuxTarget.registryDir(r.config.linux) : "-")).append('\n');
        }
        Files.createDirectories(outDir);
        String script = Templates.render("suite/install.sh", Map.of(
                "NAME", s.name.replaceAll("[\\r\\n]", " "),
                "SUITE_SH", "VI_SUITE=" + LinuxTarget.shQuote(s.name),
                "INSTALL_URL", installUrl,
                "IDS", String.join(", ", members.stream().filter(Resolved::linux).map(r -> r.config.id).toList()),
                "MEMBERS_SH", b.toString().stripTrailing()));
        InstallerGenerator.writeAscii(outDir.resolve("install.sh"), script);
    }

    private static String exampleIds(List<Resolved> members) {
        return members.size() > 1 ? members.get(0).config.id + "," + members.get(1).config.id : members.get(0).config.id;
    }

    /** Which members the Linux script leaves out unless asked for; empty when none. */
    private static String optionalNoteLinux(List<Resolved> lin) {
        List<Resolved> optional = lin.stream().filter(Resolved::optional).toList();
        if (optional.isEmpty()) return "";
        String names = and(optional.stream().map(r -> r.config.name).toList());
        String ids = and(optional.stream().map(r -> "`" + r.config.id + "`").toList());
        return "\n\n" + names + (optional.size() == 1 ? " is" : " are") + " optional: left out unless `INSTALL_ONLY` names "
                + ids + ", everything is asked for, or " + (optional.size() == 1 ? "it is" : "they are") + " installed already (then kept up to date).";
    }

    /** Which members start unticked, as a paragraph of the README; empty when none do. */
    private static String optionalNote(List<Resolved> members) {
        List<Resolved> optional = members.stream().filter(Resolved::optional).toList();
        if (optional.isEmpty()) return "";
        String names = and(optional.stream().map(r -> r.config.name).toList());
        String ids = and(optional.stream().map(r -> "`" + r.config.id + "`").toList());
        return "\n\n" + names + (optional.size() == 1 ? " starts" : " start") + " unticked, and without the window "
                + (optional.size() == 1 ? "it is" : "they are") + " left out unless already installed. Tick "
                + (optional.size() == 1 ? "it" : "them") + " to install " + (optional.size() == 1 ? "it" : "them")
                + " too, or name " + ids + " in `INSTALL_ONLY`.";
    }

    private static String and(List<String> parts) {
        if (parts.size() <= 1) return String.join("", parts);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
