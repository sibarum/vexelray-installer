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
    private record Resolved(InstallerConfig config, SuiteConfig.Member member, String manifestUrl, Path configDir, String version) {}

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
        String baseUrl = InstallerGenerator.baseUrl(s.repo, s.branchOrDefault(), s.repoPathTrimmed(), baseUrlOverride);
        String installUrl = baseUrl + "/install.ps1";
        writeScript(s, members, outDir, installUrl);

        boolean pinned = members.stream().anyMatch(r -> r.version != null);
        List<String> table = new ArrayList<>();
        table.add("| Application | What it is | Id |" + (pinned ? " Version |" : "") + " Installed to |");
        table.add("| --- | --- | --- |" + (pinned ? " --- |" : "") + " --- |");
        for (Resolved r : members) {
            String name = InstallerConfig.blank(r.member.page) ? r.config.name : "[" + r.config.name + "](" + r.member.page + ")";
            String version = !pinned ? "" : (r.version == null ? "latest" : "`" + r.version + "`") + " | ";
            table.add("| " + name + " | " + r.config.descriptionOrDefault() + " | `" + r.config.id + "` | " + version
                    + "`" + WindowsTarget.installDir(r.config, r.config.windows) + "` |");
        }
        Resolved first = members.get(0);
        Map<String, String> vars = new HashMap<>();
        vars.put("NAME", s.name);
        vars.put("DESCRIPTION", InstallerConfig.blank(s.description) ? "" : s.description.trim() + "\n");
        vars.put("SITE_LINE", s.site == null || InstallerConfig.blank(s.site.url) ? ""
                : "\nThe install page is " + s.site.url.replaceAll("/+$", "") + ", which installs with a shorter command.\n");
        vars.put("INSTALL_COMMAND", "irm " + installUrl + " | iex");
        vars.put("INSTALL_URL", installUrl);
        vars.put("MEMBER_TABLE", String.join("\n", table));
        vars.put("RERUN", pinned
                ? "Running the same line again installs these versions again, so a rebuilt snapshot replaces the one installed."
                : "Running the same line again updates each of them to its latest version.");
        vars.put("EXAMPLE_IDS", exampleIds(members));
        vars.put("PS_EXAMPLE_DIR", WindowsTarget.powershellPath(WindowsTarget.installDir(first.config, first.config.windows)));
        vars.put("EXAMPLE_ID", first.config.id);
        vars.put("REGISTRY_DIR", WindowsTarget.registryDir(first.config.windows));
        String readme = Templates.render("suite/README.md", vars);
        Path readmePath = outDir.resolve("README.md");
        if (Files.exists(readmePath)
                && !Files.readString(readmePath, StandardCharsets.UTF_8).contains(InstallerGenerator.README_MARKER)) {
            readmePath = outDir.resolve("INSTALL.md");
        }
        InstallerGenerator.writeUtf8(readmePath, readme);
        return "irm " + installUrl + " | iex";
    }

    private String runSite(SuiteConfig s, Path baseDir, Path outDir) throws IOException {
        SuiteConfig.Site site = s == null ? null : s.site;
        if (site == null || InstallerConfig.blank(site.url))
            throw new InstallerException("--site needs a 'site' section with the 'url' the page is served from");
        if (!site.url.matches("https://[^\\s\"'<>]+"))
            throw new InstallerException("'site.url' must be an https:// address, not '" + site.url + "'");
        String sections = sections(site);
        String heroAction = heroAction(site);
        Path logo = logo(site, baseDir);
        String footer = footer(s);
        List<Resolved> members = resolve(s, baseDir);
        String installUrl = site.url.replaceAll("/+$", "") + "/install.ps1";
        String command = "irm " + installUrl + " | iex";
        writeScript(s, members, outDir, installUrl);

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

        Resolved first = members.get(0);
        Map<String, String> vars = new HashMap<>();
        vars.put("NAME", html(s.name));
        String description = InstallerConfig.blank(s.description) ? "" : s.description.trim();
        vars.put("META_DESCRIPTION", attr(InstallerConfig.blank(site.tagline) ? description : site.tagline.trim()));
        vars.put("FAVICON", favicon);
        vars.put("LOGO", logoTag);
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
        vars.put("CHANGES", changes(members));
        vars.put("EXAMPLE_IDS", html(exampleIds(members)));
        vars.put("ID_LIST", idList(members));
        vars.put("PS_EXAMPLE_DIR", html(WindowsTarget.powershellPath(WindowsTarget.installDir(first.config, first.config.windows))));
        vars.put("SOURCE", InstallerConfig.blank(s.repo) ? "" : "<p>The script, the manifests and the releases are all in one public repository, <a href=\""
                + attr("https://github.com/" + s.repo) + "\">" + html(s.repo) + "</a>.</p>");
        vars.put("FEEDBACK", feedback(site));
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
            if (c.windows == null) throw new InstallerException(c.id + " has no 'windows' section; a suite installs on Windows only for now");
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

    private static String exampleIds(List<Resolved> members) {
        return members.size() > 1 ? members.get(0).config.id + "," + members.get(1).config.id : members.get(0).config.id;
    }

    // --- The landing page --------------------------------------------------------------------------------

    /** Copies the member's first binary icon as the page shows it; null when it has none. */
    private static String copyIcon(Resolved r, Path img) throws IOException {
        for (BinaryConfig b : r.config.windows.binaries == null ? List.<BinaryConfig>of() : r.config.windows.binaries) {
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
        b.append("<div><h3>").append(title).append("</h3><p>").append(prose(r.config.descriptionOrDefault()))
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

    /** The logo's file, checked; null when the site names none. */
    private static Path logo(SuiteConfig.Site site, Path baseDir) {
        if (InstallerConfig.blank(site.logo)) return null;
        Path p = Path.of(site.logo);
        if (!p.isAbsolute()) p = baseDir.resolve(p);
        if (!Files.isRegularFile(p)) throw new InstallerException("'site.logo' not found: " + p);
        if (!p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(svg|png)"))
            throw new InstallerException("'site.logo' must be an .svg or a .png: " + p);
        return p;
    }

    private static String nav(SuiteConfig.Site site, String repo) {
        StringBuilder b = new StringBuilder("<a href=\"#apps\">Applications</a>");
        for (SuiteConfig.Section sec : sectionList(site))   // checked by sections(), which runs first
            b.append("<a href=\"#").append(sec.id).append("\">")
                    .append(html(InstallerConfig.blank(sec.nav) ? sec.title.trim() : sec.nav.trim())).append("</a>");
        b.append("<a href=\"#details\">Details</a>");
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
