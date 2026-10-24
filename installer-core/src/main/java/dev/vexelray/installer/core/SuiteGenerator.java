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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a suite's one-line installer and its install page. The suite's script installs nothing itself:
 * it fetches each member's published manifest, downloads that member's own {@code install.ps1}, checks it
 * against the SHA-256 the manifest records, and runs it. So every member is installed exactly as its own
 * one-liner would install it, and what a member's install does is decided in one place, its own config.
 *
 * <p>{@link #generate} writes the script and a Markdown page for the repo the script is committed to.
 * {@link #generateSite} writes the script again, with a landing page, for a site of its own: the same
 * script, so the command can be short, and the members' manifests stay where their own runs put them.
 *
 * <p>Needs no artifacts and no network: it reads the members' {@code installer.json} files, which is
 * enough to know their names and where their manifests are published.
 */
public final class SuiteGenerator {

    /** One member, read from its installer.json. */
    private record Resolved(InstallerConfig config, SuiteConfig.Member member, String manifestUrl, Path configDir, String version) {
        /** Left out unless asked for; a pinned member never is, since a preflight installs everything built together. */
        boolean optional() { return Boolean.TRUE.equals(member.optional) && version == null; }
        boolean windows() { return config.windows != null; }
        boolean linux() { return config.linux != null; }
        /** The binaries the page takes an icon from: Windows's, or Linux's for a member without them. */
        List<BinaryConfig> binaries() {
            PlatformConfig p = config.windows != null ? config.windows : config.linux;
            return p.binaries == null ? List.of() : p.binaries;
        }
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

    /**
     * Writes the landing page described by the suite's {@code site}: {@code index.html}, the install script it
     * names, the members' icons and screenshots under {@code img/}, and {@code .nojekyll} so GitHub Pages
     * serves the folder as it is.
     *
     * @param baseDir member {@code config} and {@code image} paths are resolved against this
     * @return the command users paste
     */
    public String generateSite(SuiteConfig s, Path baseDir, Path outDir) {
        try {
            return runSite(s, baseDir, outDir);
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
        vars.put("SITE_LINE", s.site == null || InstallerConfig.blank(s.site.url) ? ""
                : "\nThe install page is " + s.site.url.replaceAll("/+$", "") + ", which installs with a shorter command.\n");
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
            w.put("OPTIONAL", optionalNote(win, false));
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
            l.put("OPTIONAL", optionalNoteLinux(lin, false));
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

    private String runSite(SuiteConfig s, Path baseDir, Path outDir) throws IOException {
        SuiteConfig.Site site = s == null ? null : s.site;
        if (site == null || InstallerConfig.blank(site.url))
            throw new InstallerException("--site needs a 'site' section with the 'url' the page is served from");
        if (!site.url.matches("https://[^\\s\"'<>]+"))
            throw new InstallerException("'site.url' must be an https:// address, not '" + site.url + "'");
        String sections = sections(site);
        String heroAction = heroAction(site);
        List<Resolved> members = resolve(s, baseDir);
        List<Resolved> win = members.stream().filter(Resolved::windows).toList();
        List<Resolved> lin = members.stream().filter(Resolved::linux).toList();
        if (win.isEmpty()) throw new InstallerException("the landing page needs at least one member with a 'windows' section");
        checkLinux(site.linux, lin);
        Path logo = logo(site.logo, "site.logo", baseDir);
        Path heroLogo = logo(site.heroLogo, "site.heroLogo", baseDir);
        String footer = footer(s);
        String installUrl = site.url.replaceAll("/+$", "") + "/install.ps1";
        String shUrl = site.url.replaceAll("/+$", "") + "/install.sh";
        String command = "irm " + installUrl + " | iex";
        writeScript(s, win, outDir, installUrl);
        if (!lin.isEmpty()) writeShScript(s, members, outDir, shUrl);

        Path img = outDir.resolve("img");
        Files.createDirectories(img);
        StringBuilder cards = new StringBuilder();
        String favicon = "", logoTag = "";
        if (logo != null) {
            String name = "logo." + logo.getFileName().toString().replaceFirst(".*\\.", "").toLowerCase(Locale.ROOT);
            Files.copy(logo, img.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            favicon = "<link rel=\"icon\" href=\"img/" + name + "\">";
            logoTag = "<img src=\"img/" + name + "\" alt=\"\">";
        }
        String heroLogoTag = "";
        if (heroLogo != null) {
            String name = "hero-logo." + heroLogo.getFileName().toString().replaceFirst(".*\\.", "").toLowerCase(Locale.ROOT);
            Files.copy(heroLogo, img.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            heroLogoTag = "<img class=\"hero-logo\" src=\"img/" + name + "\" alt=\"\">";
        }
        for (Resolved r : members) {
            String icon = copyIcon(r, img);
            if (favicon.isEmpty() && icon != null) favicon = "<link rel=\"icon\" href=\"" + attr(icon) + "\">";
            String shot = null;
            if (!InstallerConfig.blank(r.member.image)) {
                Path p = Path.of(r.member.image);
                if (!p.isAbsolute()) p = baseDir.resolve(p);
                if (!Files.isRegularFile(p)) throw new InstallerException("image for " + r.config.id + " not found: " + p);
                String name = r.config.id + "-screen." + extension(p);
                Files.copy(p, img.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                shot = "img/" + name;
            }
            cards.append(card(r, icon, shot));
        }

        Resolved first = win.get(0);
        Map<String, String> vars = new HashMap<>();
        vars.put("NAME", html(s.name));
        String description = InstallerConfig.blank(s.description) ? "" : s.description.trim();
        vars.put("META_DESCRIPTION", attr(InstallerConfig.blank(site.tagline) ? description : site.tagline.trim()));
        vars.put("FAVICON", favicon);
        vars.put("LOGO", logoTag);
        vars.put("HERO_LOGO", heroLogoTag);
        vars.put("HERO_ACTION", heroAction);
        vars.put("ACCENT", InstallerConfig.blank(site.accentColor) ? "#2563eb" : cssColor(site.accentColor));
        vars.put("NAV", nav(site, s.repo));
        vars.put("BADGE", InstallerConfig.blank(site.badge) ? "" : "<span class=\"badge\">" + html(site.badge) + "</span>");
        vars.put("TAGLINE", prose(InstallerConfig.blank(site.tagline) ? description : site.tagline));
        vars.put("INSTALL_COMMAND", html(command));
        vars.put("INSTALL_URL", attr(installUrl));
        vars.put("RUN_COMMAND", html("powershell -NoExit -Command \"" + command + "\""));
        vars.put("REQUIREMENTS", InstallerConfig.blank(site.requirements) ? ""
                : "<p class=\"hint\">" + prose(site.requirements) + "</p>");
        vars.put("DESCRIPTION", prose(description));
        vars.put("APP_CARDS", cards.toString().stripTrailing());
        vars.put("SECTIONS", sections);
        vars.put("CHANGES", changes(win));
        vars.put("EXAMPLE_IDS", html(exampleIds(win)));
        vars.put("ID_LIST", idList(win));
        vars.put("OPTIONAL", optionalNote(win, true));
        vars.put("PS_EXAMPLE_DIR", html(WindowsTarget.powershellPath(WindowsTarget.installDir(first.config, first.config.windows))));
        vars.put("SOURCE", InstallerConfig.blank(s.repo) ? "" : "<p>The script, the manifests and the releases are all in one public repository, <a href=\""
                + attr("https://github.com/" + s.repo) + "\">" + html(s.repo) + "</a>.</p>");
        vars.put("FEEDBACK", feedback(site));
        vars.put("PLATFORM_TABS", site.linux == null && lin.isEmpty() ? "" :"        <div class=\"platforms\" id=\"platforms\" role=\"tablist\" aria-label=\"Your system\" hidden>"
                + "<button type=\"button\" role=\"tab\" data-platform=\"windows\" aria-controls=\"get-windows\">Windows</button>"
                + "<button type=\"button\" role=\"tab\" data-platform=\"linux\" aria-controls=\"get-linux\">Linux</button></div>");
        vars.put("LINUX_HERO", lin.isEmpty() ? linuxHero(site.linux) : linuxInstallHero(shUrl, site.linux));
        vars.put("LINUX_DETAILS", lin.isEmpty() ? linuxDetails(site.linux) : linuxInstallDetails(shUrl, members, lin));
        vars.put("FOOTER", footer);
        InstallerGenerator.writeUtf8(outDir.resolve("index.html"), Templates.render("suite/index.html", vars));
        // Served as is: no Jekyll, which would otherwise skip files and folders it has opinions about.
        InstallerGenerator.writeUtf8(outDir.resolve(".nojekyll"), "");
        return command;
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
            members.add(new Resolved(c, m, manifestUrl, p.toAbsolutePath().getParent(), version));
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

    // --- The landing page --------------------------------------------------------------------------------

    /** Copies the member's first binary icon as the page shows it; null when it has none. */
    private static String copyIcon(Resolved r, Path img) throws IOException {
        for (BinaryConfig b : r.binaries()) {
            if (b == null || InstallerConfig.blank(b.icon)) continue;
            Path p = Path.of(b.icon);
            if (!p.isAbsolute()) p = r.configDir.resolve(p);
            if (!Files.isRegularFile(p)) throw new InstallerException("icon for " + r.config.id + " not found: " + p);
            Map.Entry<byte[], String> web = IconFiles.forWeb(p);
            String name = r.config.id + "." + web.getValue();
            Files.write(img.resolve(name), web.getKey());
            return "img/" + name;
        }
        return null;
    }

    private static String card(Resolved r, String icon, String shot) {
        String name = html(r.config.name);
        String title = InstallerConfig.blank(r.member.page) ? name
                : "<a href=\"" + attr(r.member.page) + "\">" + name + "</a>";
        StringBuilder b = new StringBuilder("        <article class=\"app\" id=\"app-").append(r.config.id).append("\">\n");
        if (shot != null) {
            String alt = InstallerConfig.blank(r.member.imageAlt) ? r.config.name : r.member.imageAlt;
            b.append("          <div class=\"shot\"><img class=\"screen\" src=\"").append(attr(shot)).append("\" alt=\"")
                    .append(attr(alt)).append("\" loading=\"lazy\"></div>\n");
        } else if (icon != null) {
            b.append("          <div class=\"shot\"><img class=\"big\" src=\"").append(attr(icon)).append("\" alt=\"\"></div>\n");
        }
        b.append("          <div class=\"app-body\">");
        if (icon != null) b.append("<img src=\"").append(attr(icon)).append("\" alt=\"\">");
        b.append("<div><h3>").append(title).append(r.optional() ? " <span class=\"opt\">optional</span>" : "").append("</h3><p>").append(prose(r.config.descriptionOrDefault()))
                .append("</p></div></div>\n        </article>\n");
        return b.toString();
    }

    /** What an install changes, said from the members' configs so it can't drift from what they do. */
    private static String changes(List<Resolved> members) {
        List<String> items = new ArrayList<>();
        Set<String> parents = new LinkedHashSet<>();
        for (Resolved r : members) {
            String dir = WindowsTarget.installDir(r.config, r.config.windows);
            int cut = dir.lastIndexOf('\\');
            parents.add(cut > 0 ? dir.substring(0, cut) : dir);
        }
        items.add(parents.size() == 1
                ? "each application in its own folder under <code>" + html(parents.iterator().next()) + "</code>"
                : "each application in its own folder");

        boolean desktop = false, startMenu = false;
        List<String> onPath = new ArrayList<>();
        List<String> opens = new ArrayList<>();
        for (Resolved r : members) {
            boolean associates = false;
            for (BinaryConfig b : r.config.windows.binaries == null ? List.<BinaryConfig>of() : r.config.windows.binaries) {
                if (b == null) continue;
                if (b.shortcuts != null) {
                    desktop |= Boolean.TRUE.equals(b.shortcuts.desktop);
                    startMenu |= Boolean.TRUE.equals(b.shortcuts.startMenu);
                }
                if (Boolean.TRUE.equals(b.addToPath)) {
                    String file = InstallerConfig.blank(b.file) ? Path.of(b.source == null ? "" : b.source).getFileName().toString() : b.file;
                    String cmd = InstallerConfig.blank(b.name) ? file.replaceFirst("\\.[^.]*$", "") : b.name;
                    if (!cmd.isEmpty()) onPath.add("<code>" + html(cmd) + "</code>");
                }
                associates |= b.fileAssociations != null && !b.fileAssociations.isEmpty();
            }
            if (associates) opens.add(html(r.config.name));
        }
        if (desktop || startMenu)
            items.add(desktop && startMenu ? "desktop and Start menu shortcuts" : desktop ? "desktop shortcuts" : "Start menu shortcuts");
        if (!onPath.isEmpty())
            items.add("your user <code>PATH</code>, so " + and(onPath) + (onPath.size() == 1 ? " works" : " work") + " from a terminal");
        if (!opens.isEmpty())
            items.add("<em>Open with</em> entries for the file types " + and(opens) + (opens.size() == 1 ? " reads" : " read")
                    + ". One becomes the default only for a type no other program has claimed: Windows does not let a new"
                    + " program take over an existing one, and neither does this");
        items.add("an entry for each application in <em>Settings &rsaquo; Apps</em>");
        StringBuilder b = new StringBuilder();
        for (String item : items) b.append("            <li>").append(item).append("</li>\n");
        return b.toString().stripTrailing();
    }

    private static String idList(List<Resolved> members) {
        return and(members.stream().map(r -> "<code>" + html(r.config.id) + "</code>").toList());
    }

    private static List<SuiteConfig.Section> sectionList(SuiteConfig.Site site) {
        return site.sections == null ? List.of() : site.sections;
    }

    /** The suite's own sections. Called before anything is written, so a mistake in any of them leaves the folder alone. */
    private static String sections(SuiteConfig.Site site) {
        Set<String> ids = new HashSet<>(List.of("apps", "details"));
        for (SuiteConfig.Section sec : sectionList(site)) {
            if (sec == null || InstallerConfig.blank(sec.title))
                throw new InstallerException("every 'site.sections' entry needs a 'title'");
            if (sec.id == null || !sec.id.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*"))
                throw new InstallerException("section '" + sec.title + "' needs an 'id' of lowercase letters, digits and dashes");
            if (!ids.add(sec.id))
                throw new InstallerException("the page already has a section with the id '" + sec.id + "'");
            for (SuiteConfig.Card c : sec.cards == null ? List.<SuiteConfig.Card>of() : sec.cards)
                if (c == null || InstallerConfig.blank(c.title) || InstallerConfig.blank(c.text))
                    throw new InstallerException("every card in section '" + sec.id + "' needs a 'title' and a 'text'");
            if (sec.action != null && (InstallerConfig.blank(sec.action.label) || InstallerConfig.blank(sec.action.url)))
                throw new InstallerException("the 'action' of section '" + sec.id + "' needs a 'label' and a 'url'");
        }
        StringBuilder b = new StringBuilder();
        for (SuiteConfig.Section sec : sectionList(site)) {
            String title = sec.id + "-title";
            String eyebrow = InstallerConfig.blank(sec.nav) ? sec.title.trim() : sec.nav.trim();
            b.append("    <section class=\"band\" id=\"").append(sec.id).append("\" aria-labelledby=\"").append(title).append("\">\n")
                    .append("      <div class=\"wrap\">\n")
                    .append("      <div class=\"eyebrow\">").append(html(eyebrow)).append("</div>\n")
                    .append("      <h2 id=\"").append(title).append("\">").append(html(sec.title.trim())).append("</h2>\n");
            if (!InstallerConfig.blank(sec.lede)) b.append("      <p class=\"lede\">").append(prose(sec.lede)).append("</p>\n");
            if (sec.cards != null && !sec.cards.isEmpty()) {
                b.append("      <div class=\"cards\">\n");
                for (SuiteConfig.Card c : sec.cards) {
                    String name = html(c.title.trim());
                    if (!InstallerConfig.blank(c.url)) name = "<a href=\"" + attr(c.url) + "\">" + name + "</a>";
                    b.append("        <article class=\"card\">");
                    if (!InstallerConfig.blank(c.stat)) b.append("<span class=\"stat\">").append(html(c.stat.trim())).append("</span>");
                    b.append("<h3>").append(name).append("</h3><p>").append(prose(c.text)).append("</p></article>\n");
                }
                b.append("      </div>\n");
            }
            if (sec.action != null)
                b.append("      <p class=\"action\"><a class=\"button\" href=\"").append(attr(sec.action.url)).append("\">")
                        .append(html(sec.action.label)).append("</a></p>\n");
            b.append("      </div>\n    </section>\n\n");
        }
        return b.toString().stripTrailing();
    }

    private static String heroAction(SuiteConfig.Site site) {
        SuiteConfig.Link a = site.heroAction;
        if (a == null) return "";
        if (InstallerConfig.blank(a.label) || InstallerConfig.blank(a.url))
            throw new InstallerException("'site.heroAction' needs a 'label' and a 'url'");
        return "<a class=\"button ghost\" href=\"" + attr(a.url) + "\">" + html(a.label) + "</a>";
    }

    /** Called before anything is written, like {@link #sections}. */
    private static void checkLinux(SuiteConfig.Download d, List<Resolved> lin) {
        if (d == null) return;
        if (!lin.isEmpty()) {
            // with an install line for Linux there is nothing to download by hand; what a computer needs still applies
            if (!InstallerConfig.blank(d.url) || !InstallerConfig.blank(d.label) || !InstallerConfig.blank(d.text)
                    || d.steps != null && !d.steps.isEmpty())
                throw new InstallerException("'site.linux' offers downloads for a suite without a Linux installer, but "
                        + and(lin.stream().map(r -> r.config.id).toList()) + (lin.size() == 1 ? " has" : " have")
                        + " a 'linux' section, so the page shows the install line; keep only site.linux.requirements");
            return;
        }
        if (InstallerConfig.blank(d.url) || !d.url.matches("https://[^\\s\"'<>]+"))
            throw new InstallerException("'site.linux' needs a 'url': the https:// address of the builds");
        if (d.steps != null && d.steps.stream().anyMatch(InstallerConfig::blank))
            throw new InstallerException("every 'site.linux.steps' entry needs some text");
    }

    /** The hero's Linux panel, beside the Windows install line; empty when the site offers no Linux builds. */
    private static String linuxHero(SuiteConfig.Download d) {
        if (d == null) return "";
        String label = InstallerConfig.blank(d.label) ? "Download" : d.label.trim();
        StringBuilder b = new StringBuilder("        <div class=\"platform\" id=\"get-linux\">\n")
                .append("        <p class=\"install-label\"><strong>Linux:</strong>");
        if (!InstallerConfig.blank(d.text)) b.append(' ').append(prose(d.text));
        b.append("</p>\n        <p class=\"downloads\"><a class=\"button\" href=\"").append(attr(d.url)).append("\">")
                .append(html(label)).append("</a>");
        if (d.steps != null && !d.steps.isEmpty())
            b.append("<a class=\"button ghost\" href=\"#linux\">How to run them</a>");
        b.append("</p>\n");
        if (!InstallerConfig.blank(d.requirements)) b.append("        <p class=\"hint\">").append(prose(d.requirements)).append("</p>\n");
        return b.append("        </div>").toString();
    }

    /** The hero's Linux panel when the members have a Linux installer: the line to paste into a terminal. */
    private static String linuxInstallHero(String shUrl, SuiteConfig.Download d) {
        StringBuilder b = new StringBuilder("        <div class=\"platform\" id=\"get-linux\">\n")
                .append("        <p class=\"install-label\"><strong>Linux:</strong> paste this into a terminal and press <kbd>Enter</kbd></p>\n")
                .append("        <div class=\"cmd\"><code id=\"install-command-linux\">").append(html("curl -fsSL " + shUrl + " | sh"))
                .append("</code><button class=\"copy\" type=\"button\" data-copy=\"install-command-linux\">Copy</button></div>\n")
                .append("        <p class=\"hint\">No <code>sudo</code>: everything goes under <code>~/.local</code>, for your user."
                        + " Running the same line again updates everything.</p>\n")
                .append("        <ul class=\"facts\">\n          <li>No root</li>\n          <li>Your user only</li>\n"
                        + "          <li>Every download checked by SHA-256</li>\n          <li>Uninstall with one command</li>\n        </ul>\n");
        if (d != null && !InstallerConfig.blank(d.requirements)) b.append("        <p class=\"hint\">").append(prose(d.requirements)).append("</p>\n");
        return b.append("        </div>").toString();
    }

    /** What the Linux line changes, how to choose and how to undo it, as one of the install details. */
    private static String linuxInstallDetails(String shUrl, List<Resolved> members, List<Resolved> lin) {
        Set<String> parents = new LinkedHashSet<>();
        boolean menu = false, desktop = false;
        List<String> onPath = new ArrayList<>(), opens = new ArrayList<>();
        for (Resolved r : lin) {
            String dir = LinuxTarget.displayPath(LinuxTarget.installDir(r.config, r.config.linux));
            parents.add(dir.contains("/") ? dir.substring(0, dir.lastIndexOf('/')) : dir);
            boolean associates = false;
            for (BinaryConfig b : r.config.linux.binaries == null ? List.<BinaryConfig>of() : r.config.linux.binaries) {
                if (b == null) continue;
                if (b.shortcuts != null) {
                    menu |= Boolean.TRUE.equals(b.shortcuts.startMenu);
                    desktop |= b.shortcuts.desktop == null || b.shortcuts.desktop;
                }
                if (Boolean.TRUE.equals(b.addToPath)) {
                    String file = InstallerConfig.blank(b.file) ? Path.of(b.source == null ? "" : b.source).getFileName().toString() : b.file;
                    String cmd = InstallerConfig.blank(b.name) ? file.replaceFirst("\\.[^.]*$", "") : b.name;
                    if (!cmd.isEmpty()) onPath.add("<code>" + html(cmd) + "</code>");
                }
                associates |= b.fileAssociations != null && !b.fileAssociations.isEmpty();
            }
            if (associates) opens.add(html(r.config.name));
        }
        List<String> items = new ArrayList<>();
        items.add(parents.size() == 1 ? "each application in its own folder under <code>" + html(parents.iterator().next()) + "</code>"
                : "each application in its own folder");
        if (menu || desktop) items.add(menu && desktop ? "entries in your applications menu, and launchers on your desktop if you have a Desktop folder"
                : menu ? "entries in your applications menu" : "launchers on your desktop, if you have a Desktop folder");
        if (!onPath.isEmpty()) items.add("links in <code>~/.local/bin</code>, so " + and(onPath) + (onPath.size() == 1 ? " runs" : " run")
                + " from a terminal; a link is never put over a file that is not its own");
        if (!opens.isEmpty()) items.add("file types " + and(opens) + (opens.size() == 1 ? " opens" : " open")
                + ", under <em>Open With</em>; one becomes the default only where no program is");
        List<String> windowsOnly = members.stream().filter(r -> !r.linux()).map(r -> html(r.config.name)).toList();
        String example = lin.size() > 1 ? lin.get(0).config.id + "," + lin.get(1).config.id : lin.get(0).config.id;
        String uninstall = "\"" + LinuxTarget.shellPath(LinuxTarget.installDir(lin.get(0).config, lin.get(0).config.linux)) + "/uninstall.sh\" --all";
        StringBuilder b = new StringBuilder("        <details id=\"linux\">\n")
                .append("          <summary>On Linux</summary>\n")
                .append("          <p>The line runs one script, <a href=\"").append(attr(shUrl)).append("\">install.sh</a>. Like the Windows one, it")
                .append(" runs each application's own installer once its SHA-256 matches the application's manifest, and that installer")
                .append(" checks every download the same way. Nothing needs root, and everything is undone by uninstalling:</p>\n          <ul>\n");
        for (String item : items) b.append("            <li>").append(item).append("</li>\n");
        b.append("          </ul>\n");
        if (!windowsOnly.isEmpty())
            b.append("          <p>").append(and(windowsOnly)).append(windowsOnly.size() == 1 ? " has" : " have").append(" no Linux version yet.</p>\n");
        b.append("          <p>Options go between the pipe and <code>sh</code>. Only some of the applications:</p>\n")
                .append("          <div class=\"cmd plain\"><code id=\"linux-only\">").append(html("curl -fsSL " + shUrl + " | INSTALL_ONLY=" + example + " sh"))
                .append("</code><button class=\"copy\" type=\"button\" data-copy=\"linux-only\">Copy</button></div>\n")
                .append("          <p>Everything, the optional ones too:</p>\n")
                .append("          <div class=\"cmd plain\"><code id=\"linux-everything\">").append(html("curl -fsSL " + shUrl + " | sh -s -- --install-everything"))
                .append("</code><button class=\"copy\" type=\"button\" data-copy=\"linux-everything\">Copy</button></div>\n")
                .append("          <p><code>INSTALL_NO_SHORTCUTS=1</code>, <code>INSTALL_NO_PATH=1</code> and <code>INSTALL_NO_ASSOCIATIONS=1</code>")
                .append(" leave those out the same way.").append(optionalNoteLinux(lin, true))
                .append(" Run the line again to update. To remove everything:</p>\n")
                .append("          <div class=\"cmd plain\"><code id=\"linux-uninstall\">").append(html(uninstall))
                .append("</code><button class=\"copy\" type=\"button\" data-copy=\"linux-uninstall\">Copy</button></div>\n")
                .append("        </details>");
        return b.toString();
    }

    /** Which members the Linux script leaves out unless asked for; empty when none. */
    private static String optionalNoteLinux(List<Resolved> lin, boolean asHtml) {
        List<Resolved> optional = lin.stream().filter(Resolved::optional).toList();
        if (optional.isEmpty()) return "";
        String names = and(optional.stream().map(r -> asHtml ? html(r.config.name) : r.config.name).toList());
        String ids = and(optional.stream().map(r -> asHtml ? "<code>" + html(r.config.id) + "</code>" : "`" + r.config.id + "`").toList());
        String only = asHtml ? "<code>INSTALL_ONLY</code>" : "`INSTALL_ONLY`";
        String text = names + (optional.size() == 1 ? " is" : " are") + " optional: left out unless " + only + " names "
                + ids + ", everything is asked for, or " + (optional.size() == 1 ? "it is" : "they are") + " installed already (then kept up to date).";
        return asHtml ? " " + text : "\n\n" + text;
    }

    /** The Linux steps, as one of the install details; empty when there are none. */
    private static String linuxDetails(SuiteConfig.Download d) {
        if (d == null || d.steps == null || d.steps.isEmpty()) return "";
        StringBuilder b = new StringBuilder("        <details id=\"linux\">\n")
                .append("          <summary>Run them on Linux</summary>\n")
                .append("          <p>Linux has no install line yet. The builds are on <a href=\"").append(attr(d.url))
                .append("\">their download page</a>; from there:</p>\n          <ol>\n");
        for (String step : d.steps) b.append("            <li>").append(prose(step)).append("</li>\n");
        b.append("          </ol>\n");
        if (!InstallerConfig.blank(d.requirements)) b.append("          <p>").append(prose(d.requirements)).append("</p>\n");
        return b.append("        </details>").toString();
    }

    /** A logo's file, checked; null when the site names none. {@code key} is its name in the config, for errors. */
    private static Path logo(String value, String key, Path baseDir) {
        if (InstallerConfig.blank(value)) return null;
        Path p = Path.of(value);
        if (!p.isAbsolute()) p = baseDir.resolve(p);
        if (!Files.isRegularFile(p)) throw new InstallerException("'" + key + "' not found: " + p);
        if (!p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(svg|png)"))
            throw new InstallerException("'" + key + "' must be an .svg or a .png: " + p);
        return p;
    }

    /** The header: the members, then the site's links. The page's own sections get none; it scrolls to them. */
    private static String nav(SuiteConfig.Site site, String repo) {
        StringBuilder b = new StringBuilder("<a href=\"#apps\">Applications</a>");
        for (SuiteConfig.Link l : site.links == null ? List.<SuiteConfig.Link>of() : site.links) {
            if (l == null || InstallerConfig.blank(l.label) || InstallerConfig.blank(l.url))
                throw new InstallerException("every 'site.links' entry needs a 'label' and a 'url'");
            b.append("<a href=\"").append(attr(l.url)).append("\">").append(html(l.label)).append("</a>");
        }
        return b.toString();
    }

    private static String feedback(SuiteConfig.Site site) {
        if (InstallerConfig.blank(site.feedbackUrl)) return "";
        return """
                <details id="feedback">
                          <summary>Something went wrong, or you have feedback</summary>
                          <p>Anything is useful: it did not install, a window looked wrong, something was confusing, or you just liked it. <a href="%s">Open an issue</a> and say which application, what you did and what happened. A screenshot helps.</p>
                        </details>""".formatted(attr(site.feedbackUrl));
    }

    private static String footer(SuiteConfig s) {
        String source = InstallerConfig.blank(s.repo) ? ""
                : " Source of the installers: <a href=\"" + attr("https://github.com/" + s.repo) + "\">" + html(s.repo) + "</a>.";
        StringBuilder b = new StringBuilder("<p>Generated by vexelray-installer.").append(source);
        SuiteConfig.Site site = s.site;
        for (SuiteConfig.Link l : site == null || site.footerLinks == null ? List.<SuiteConfig.Link>of() : site.footerLinks) {
            if (l == null || InstallerConfig.blank(l.label) || InstallerConfig.blank(l.url))
                throw new InstallerException("every 'site.footerLinks' entry needs a 'label' and a 'url'");
            b.append(" &middot; <a href=\"").append(attr(l.url)).append("\">").append(html(l.label)).append("</a>");
        }
        b.append("</p>");
        if (site != null && !InstallerConfig.blank(site.notice)) b.append("\n  <p class=\"legal\">").append(prose(site.notice)).append("</p>");
        return b.toString();
    }

    /** Which members start unticked, as a paragraph of the README or the page; empty when none do. */
    private static String optionalNote(List<Resolved> members, boolean asHtml) {
        List<Resolved> optional = members.stream().filter(Resolved::optional).toList();
        if (optional.isEmpty()) return "";
        String names = and(optional.stream().map(r -> asHtml ? html(r.config.name) : r.config.name).toList());
        String ids = and(optional.stream().map(r -> asHtml ? "<code>" + html(r.config.id) + "</code>" : "`" + r.config.id + "`").toList());
        String only = asHtml ? "<code>INSTALL_ONLY</code>" : "`INSTALL_ONLY`";
        String text = names + (optional.size() == 1 ? " starts" : " start") + " unticked, and without the window "
                + (optional.size() == 1 ? "it is" : "they are") + " left out unless already installed. Tick "
                + (optional.size() == 1 ? "it" : "them") + " to install " + (optional.size() == 1 ? "it" : "them")
                + " too, or name " + ids + " in " + only + ".";
        return asHtml ? "\n          <p>" + text + "</p>" : "\n\n" + text;
    }

    private static String and(List<String> parts) {
        if (parts.size() <= 1) return String.join("", parts);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        if (!ext.matches("png|jpe?g|gif|webp|avif|svg"))
            throw new InstallerException("a screenshot must be a .png, .jpg, .gif, .webp, .avif or .svg: " + p);
        return ext;
    }

    private static String cssColor(String c) {
        String v = c.trim();
        if (!v.matches("#[0-9A-Fa-f]{3,8}|[a-z]+|(rgb|hsl)a?\\([0-9.,%\\s]+\\)"))
            throw new InstallerException("'site.accentColor' must be a CSS colour, not '" + c + "'");
        return v;
    }

    private static final Pattern TICKS = Pattern.compile("`([^`]+)`");

    /** Plain text with {@code `code`} spans, as the configs write it. */
    static String prose(String text) {
        Matcher m = TICKS.matcher(html(text.trim()));
        return m.replaceAll(r -> Matcher.quoteReplacement("<code>" + r.group(1) + "</code>"));
    }

    static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String attr(String text) { return html(text).replace("\"", "&quot;"); }
}
