package dev.vexelray.installer.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-user Linux install driven by POSIX sh scripts ({@code curl -fsSL <url> | sh}): no root, everything under the
 * user's home. The binaries go to {@code ~/.local/opt/<id>}, commands are linked into {@code ~/.local/bin}, menu entries
 * and file types follow the freedesktop.org specifications, and the install record is the same JSON the Windows
 * installer writes, in {@code $XDG_DATA_HOME/vexelray-installer/installs}.
 *
 * <p>sh cannot read JSON, so beside {@code manifest.json} each run writes {@value #MANIFEST}: the Linux part of every
 * version, one tab-separated record per line, which the scripts read with {@code read} and {@code awk}. It is made from
 * the merged manifest, so it always says what {@code manifest.json} says.
 */
public final class LinuxTarget implements PlatformTarget {
    static final String DEFAULT_INSTALL_DIR = "$HOME/.local/opt/{id}";
    static final String DEFAULT_REGISTRY_DIR = "$XDG_DATA_HOME/vexelray-installer/installs";
    static final String MANIFEST = "manifest.linux.txt";
    /** The first line of {@value #MANIFEST}; the scripts refuse any other. */
    static final String MANIFEST_HEADER = "vexelray-installer-linux\t1";

    @Override public String key() { return "linux"; }

    @Override public PlatformConfig configOf(InstallerConfig config) { return config.linux; }

    @Override public List<String> scriptNames() { return List.of("install.sh", "uninstall.sh", "update.sh"); }

    @Override public String iconFormat() { return "png"; }

    /** Where the scripts read their manifest: {@value #MANIFEST} beside {@code manifest.json}. */
    static String manifestUrl(String jsonManifestUrl) {
        return jsonManifestUrl.replaceFirst("manifest\\.json$", MANIFEST);
    }

    @Override
    public ObjectNode scriptConfig(InstallerConfig c, PlatformConfig p, String manifestUrl) {
        ObjectNode n = Json.object();
        n.put("id", c.id);
        n.put("name", c.name);
        n.put("publisher", c.publisherOrName());
        n.put("description", c.descriptionOrDefault());
        n.put("manifestUrl", manifestUrl(manifestUrl));
        n.put("registryDir", registryDir(p));
        String issues = c.issuesUrlOrDefault();
        if (issues != null) n.put("issuesUrl", issues);
        return n;
    }

    /**
     * {@code CONFIG_SH}: the same values as sh assignments, each quoted so the shell takes it literally. Every script
     * gets all of them and uses only some, so they are one group that shellcheck is told not to call unused.
     */
    @Override
    public Map<String, String> scriptVars(InstallerConfig c, PlatformConfig p, String manifestUrl) {
        ObjectNode n = scriptConfig(c, p, manifestUrl);
        StringBuilder b = new StringBuilder("# shellcheck disable=SC2034\n{\n");
        for (String[] kv : new String[][] {{"VI_ID", "id"}, {"VI_NAME", "name"}, {"VI_PUBLISHER", "publisher"},
                {"VI_DESCRIPTION", "description"}, {"VI_MANIFEST_URL", "manifestUrl"}, {"VI_REGISTRY_DIR", "registryDir"},
                {"VI_ISSUES_URL", "issuesUrl"}}) {
            b.append(kv[0]).append('=').append(shQuote(n.path(kv[1]).asText(""))).append('\n');
        }
        return Map.of("CONFIG_SH", b.append('}').toString());
    }

    /**
     * A word sh reads as exactly {@code s}: single-quoted, or for text that is not ASCII (the scripts must stay ASCII)
     * spelled out byte by byte through {@code printf}.
     */
    static String shQuote(String s) {
        if (s.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f || ch == '\t'))
            return "'" + s.replace("'", "'\\''") + "'";
        StringBuilder b = new StringBuilder("\"$(printf '");
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int u = x & 0xff;
            if (Character.isLetterOrDigit(u) && u < 0x80 || u == ' ' || u == '.' || u == '-' || u == '_') b.append((char) u);
            else b.append('\\').append(String.format(Locale.ROOT, "%03o", u));
        }
        return b.append("')\"").toString();
    }

    static String registryDir(PlatformConfig p) {
        return InstallerConfig.blank(p.registryDir) ? DEFAULT_REGISTRY_DIR : p.registryDir.trim();
    }

    static String installDir(InstallerConfig c, PlatformConfig p) {
        return (InstallerConfig.blank(p.installDir) ? DEFAULT_INSTALL_DIR : p.installDir.trim())
                .replace("{name}", c.name).replace("{id}", c.id);
    }

    /** A path from the config as a shell reads it inside double quotes: {@code ~/} is {@code $HOME/}, and XDG has its default. */
    static String shellPath(String path) {
        return path.replaceFirst("^~(?=/|$)", "\\$HOME").replace("$XDG_DATA_HOME", "${XDG_DATA_HOME:-$HOME/.local/share}")
                .replace("${XDG_DATA_HOME}", "${XDG_DATA_HOME:-$HOME/.local/share}");
    }

    /** The same path as a person reads it: {@code ~/.local/...}. */
    static String displayPath(String path) {
        return path.replace("$XDG_DATA_HOME", "~/.local/share").replace("${XDG_DATA_HOME}", "~/.local/share")
                .replaceFirst("^\\$HOME(?=/|$)", "~").replaceFirst("^\\$\\{HOME}(?=/|$)", "~");
    }

    /** The processor the install is for; null when everything in it is a jar or a file. */
    static String arch(PlatformConfig p, List<ResolvedBinary> bins) {
        if (!InstallerConfig.blank(p.arch)) return p.arch.trim();
        return bins.stream().anyMatch(b -> !b.isJar() && !b.isFile()) ? "x86_64" : null;
    }

    private static String sub(BinaryConfig bc) {
        return bc.dir == null ? "" : bc.dir.replace('\\', '/').replaceAll("^/+|/+$", "");
    }

    /** {@code {installDir}/sub/file}, or {@code {installDir}/sub} for an archive. */
    private static String installPath(String sub, String file) {
        String dir = sub.isEmpty() ? "{installDir}" : "{installDir}/" + sub;
        return file == null ? dir : dir + "/" + file;
    }

    /** The jar's launcher, a shell script named after the binary beside it. */
    private static String launcher(ResolvedBinary b) { return installPath(sub(b.config()), b.name()); }

    /** The type a file association names, or one made up for it ({@code application/x-<id>-<ext>}). */
    static String mimeType(InstallerConfig c, FileAssociation fa) {
        if (!InstallerConfig.blank(fa.contentType)) return fa.contentType.trim().toLowerCase(Locale.ROOT);
        return "application/x-" + c.id + "-" + InstallerGenerator.normalizeExtension(fa.extension).substring(1);
    }

    @Override
    public void check(InstallerConfig c, PlatformConfig p, List<ResolvedBinary> bins) {
        if (!InstallerConfig.blank(p.arch) && !p.arch.trim().matches("[a-z0-9_]+"))
            throw new InstallerException("'arch' is what uname -m prints, such as x86_64 or aarch64, not '" + p.arch + "'");
        if (p.system != null && (!InstallerConfig.blank(p.system.windows) || !InstallerConfig.blank(p.system.d3d12)))
            throw new InstallerException("system.windows and system.d3d12 are Windows requirements; the linux section cannot set them");
        for (String path : new String[] {installDir(c, p), registryDir(p)}) {
            line(path, "the install or registry folder");
            if (!path.matches("(/|~/|\\$HOME/|\\$\\{HOME}/|\\$XDG_DATA_HOME/|\\$\\{XDG_DATA_HOME}/).*"))
                throw new InstallerException("a Linux folder starts with /, ~/, $HOME/ or $XDG_DATA_HOME/, not '" + path + "'");
        }
        line(c.name, "the name");
        line(c.publisherOrName(), "the publisher");
        List<String> paths = new ArrayList<>();
        List<String> appIds = new ArrayList<>();
        for (ResolvedBinary b : bins) {
            BinaryConfig bc = b.config();
            line(b.name(), "a binary name");
            line(b.file(), "a file name");
            line(bc.dir, "a binary's dir");
            if (b.isArchive() && !InstallerGenerator.TARBALL.matcher(b.file()).matches())
                throw new InstallerException("a Linux archive must be a .tar.gz (" + b.file() + "): tar is on every Linux, unzip is not");
            if (!InstallerConfig.blank(bc.signedBy))
                throw new InstallerException("'signedBy' is Authenticode, which Linux does not have (" + b.file() + ")");
            if (b.isArchive()) continue;
            paths.add(installPath(sub(bc), b.file()));
            if (b.isJar()) paths.add(launcher(b));
            if (bc.shortcuts != null) line(bc.shortcuts.label, "a shortcut label");
            if (!InstallerConfig.blank(bc.appId)) {
                String id = bc.appId.trim();
                if (!APP_ID.matcher(id).matches())
                    throw new InstallerException("'" + id + "' cannot be an appId: it names a desktop entry, so it is"
                            + " dot-separated parts of letters, digits, _ and -, none starting with a digit (" + b.name() + ")");
                if (appIds.contains(id))
                    throw new InstallerException("two binaries have the appId " + id + "; each program's windows go by their own");
                appIds.add(id);
            }
            if (bc.jvmArgs != null) for (String a : bc.jvmArgs) line(a, "a jvmArgs entry");
            if (bc.fileAssociations != null) for (FileAssociation fa : bc.fileAssociations) {
                line(fa.description, "a file association description");
                if (fa.description != null && fa.description.matches("(?s).*[<>&].*"))
                    throw new InstallerException("a file association description cannot hold < > or & (" + fa.extension + ")");
            }
        }
        for (int i = 0; i < paths.size(); i++)
            if (paths.subList(0, i).contains(paths.get(i)))
                throw new InstallerException("two files would be installed as " + paths.get(i).replace("{installDir}/", "")
                        + " (a jar's launcher is named after the binary); give one of them another name or dir");
    }

    /** What a desktop entry name may be (the Desktop Entry Specification, "Desktop File ID"), without its {@code .desktop}. */
    static final java.util.regex.Pattern APP_ID =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)+");

    /** One line, no tabs: the manifest the scripts read is tab-separated, one record a line. */
    private static void line(String s, String what) {
        if (s != null && s.matches("(?s).*[\\t\\r\\n].*"))
            throw new InstallerException(what + " cannot hold tabs or line breaks: " + s);
    }

    @Override
    public ObjectNode manifestEntry(InstallerConfig c, PlatformConfig p, List<ResolvedBinary> bins) {
        ObjectNode platform = Json.object();
        platform.put("installDir", installDir(c, p));
        String arch = arch(p, bins);
        if (arch != null) platform.put("arch", arch);

        int javaMin = -1;
        Map<String, String> needs = new LinkedHashMap<>();
        for (Requirement rq : p.requires == null ? List.<Requirement>of() : p.requires)
            needs.put(rq.id, InstallerConfig.blank(rq.min) ? "" : Versions.strip(rq.min));
        ArrayNode binaries = Json.NODES.arrayNode();
        for (ResolvedBinary b : bins) {
            BinaryConfig bc = b.config();
            String sub = sub(bc);
            ObjectNode e = Json.object();
            e.put("name", b.name());
            e.put("version", b.version());
            e.put("kind", b.kind());
            e.put("file", b.file());
            e.put("url", b.url());
            e.put("sha256", b.sha256());
            e.put("size", b.size());
            e.put("installPath", b.isArchive() ? installPath(sub, null) : installPath(sub, b.file()));
            e.put("executable", !b.isArchive() && !b.isFile());
            if (b.isArchive() && bc.exposes != null && !bc.exposes.isEmpty()) {
                ObjectNode ex = e.putObject("exposes");
                bc.exposes.forEach((cmd, rel) -> ex.put(cmd, rel.replace('\\', '/')));
            }
            e.put("addToPath", Boolean.TRUE.equals(bc.addToPath));
            if (!InstallerConfig.blank(bc.appId)) e.put("appId", bc.appId.trim());
            if (b.icon() != null) iconEntry(e.putObject("icon"), b.icon(), sub);
            List<ResolvedBinary.ResolvedIcon> typeIcons = b.distinctTypeIcons();
            if (!typeIcons.isEmpty()) {
                ArrayNode fi = e.putArray("fileIcons");
                for (ResolvedBinary.ResolvedIcon ti : typeIcons) iconEntry(fi.addObject(), ti, sub);
            }
            if (b.isJar()) {
                e.put("launcher", launcher(b));
                if (InstallerConfig.blank(bc.runtime)) javaMin = Math.max(javaMin, bc.javaMin == null ? 0 : bc.javaMin);
                else {
                    e.put("runtime", bc.runtime);
                    needs.putIfAbsent(bc.runtime, "");
                }
                if (bc.jvmArgs != null && !bc.jvmArgs.isEmpty()) {
                    ArrayNode ja = e.putArray("jvmArgs");
                    bc.jvmArgs.forEach(ja::add);
                }
            }
            ShortcutConfig sc = bc.shortcuts;
            if (sc != null) {
                ObjectNode s = e.putObject("shortcuts");
                s.put("desktop", sc.desktop == null || sc.desktop);
                s.put("startMenu", Boolean.TRUE.equals(sc.startMenu));
                s.put("label", InstallerConfig.blank(sc.label) ? b.name() : sc.label);
                // a menu entry opens no terminal unless asked for: on Linux nothing is a console program by its file
                s.put("gui", sc.gui == null || sc.gui);
            }
            if (bc.fileAssociations != null && !bc.fileAssociations.isEmpty()) {
                ArrayNode fas = e.putArray("fileAssociations");
                for (FileAssociation fa : bc.fileAssociations) {
                    String ext = InstallerGenerator.normalizeExtension(fa.extension);
                    ObjectNode n = fas.addObject();
                    n.put("extension", ext);
                    n.put("mimeType", mimeType(c, fa));
                    // a type made up here is this install's own: it gets a name and an icon, and goes with the install
                    n.put("ownType", InstallerConfig.blank(fa.contentType));
                    n.put("description", InstallerConfig.blank(fa.description)
                            ? c.name + " " + ext.substring(1).toUpperCase(Locale.ROOT) + " file" : fa.description.trim());
                    ResolvedBinary.ResolvedIcon ti = b.typeIcons().get(ext);
                    if (ti != null) n.put("icon", ti.file());
                }
            }
            binaries.add(e);
        }
        boolean system = p.system != null && !p.system.isEmpty();
        if (javaMin >= 0 || !needs.isEmpty() || system) {
            ObjectNode req = platform.putObject("requires");
            if (system) req.set("system", systemNode(p.system));
            if (javaMin >= 0) req.put("java", javaMin);
            if (!needs.isEmpty()) {
                ArrayNode installs = req.putArray("installs");
                needs.forEach((id, min) -> {
                    ObjectNode n = installs.addObject();
                    n.put("id", id);
                    if (!min.isEmpty()) n.put("min", min);
                });
            }
        }
        platform.set("binaries", binaries);
        return platform;
    }

    static ObjectNode systemNode(SystemRequirements s) {
        ObjectNode n = Json.object();
        if (!InstallerConfig.blank(s.glibc)) n.put("glibc", s.glibc.trim());
        if (s.memoryMb != null) n.put("memoryMb", s.memoryMb);
        if (s.diskMb != null) n.put("diskMb", s.diskMb);
        if (!InstallerConfig.blank(s.vulkan)) n.put("vulkan", s.vulkan.trim());
        if (s.vulkanExtensions != null && !s.vulkanExtensions.isEmpty()) {
            ArrayNode e = n.putArray("vulkanExtensions");
            s.vulkanExtensions.forEach(e::add);
        }
        return n;
    }

    private static void iconEntry(ObjectNode ic, ResolvedBinary.ResolvedIcon icon, String sub) {
        ic.put("file", icon.file());
        ic.put("url", icon.url());
        ic.put("sha256", icon.sha256());
        ic.put("size", icon.size());
        ic.put("installPath", installPath(sub, icon.file()));
        try {
            // an icon theme files icons by their size (hicolor/256x256/...)
            ic.put("pixels", IconFiles.pngSize(icon.staged()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- manifest.linux.txt -----------------------------------------------------------------------------

    /**
     * {@value #MANIFEST}, from the merged manifest. One record a line, its fields separated by tabs; a field that is
     * empty is {@code -}, so {@code IFS=<tab> read} (which merges empty fields) reads every line right. A free-text
     * field (a path, a label) comes last, where {@code read} gives it the rest of the line.
     *
     * <pre>
     * vexelray-installer-linux  1
     * id        ID
     * name      NAME
     * replaces  ID                             an id this application was installed under before (one each)
     * latest    VERSION                        newest release with a Linux build (else the newest)
     * tool      SCRIPT  SHA256  URL
     * version   VERSION                        then, for that version:
     * dir       VERSION  INSTALL-DIR
     * arch      VERSION  ARCH
     * java      VERSION  MIN-MAJOR
     * system    VERSION  KEY  VALUE            glibc, memoryMb, diskMb, vulkan, vulkanExtension (one each)
     * needs     VERSION  ID  MIN
     * bin       VERSION  NAME  KIND  BIN-VERSION  SHA256  SIZE  ADD-TO-PATH  FILE  URL  PATH
     * icon      VERSION  BIN  SHA256  PIXELS  FILE  URL  PATH
     * typeicon  VERSION  BIN  SHA256  PIXELS  FILE  URL  PATH
     * launcher  VERSION  BIN  PATH
     * runtime   VERSION  BIN  ID
     * jvmarg    VERSION  BIN  ARG
     * shortcut  VERSION  BIN  DESKTOP  MENU  GUI  LABEL
     * appid     VERSION  BIN  APP-ID           names its desktop entry, and is the entry's StartupWMClass
     * expose    VERSION  BIN  COMMAND  PATH
     * assoc     VERSION  BIN  EXTENSION  MIME-TYPE  OWN-TYPE  ICON-FILE  DESCRIPTION
     * </pre>
     * PATH is relative to the install folder ({@code .} for the folder itself); flags are {@code 1} or {@code 0}.
     */
    @Override
    public Map<String, String> companionFiles(InstallerConfig c, ObjectNode manifest) {
        List<String> out = new ArrayList<>();
        out.add(MANIFEST_HEADER);
        out.add(rec("id", c.id));
        out.add(rec("name", c.name));
        for (JsonNode was : manifest.path("replaces")) out.add(rec("replaces", was.asText()));
        String latest = null, newest = null;
        for (JsonNode v : manifest.path("versions")) {
            if (v.path("platforms").path("linux").isMissingNode()) continue;
            String ver = v.path("version").asText();
            if (newest == null) newest = ver;
            if (latest == null && !Versions.isPre(ver)) latest = ver;
        }
        out.add(rec("latest", latest != null ? latest : newest));
        manifest.path("tools").fields().forEachRemaining(t -> {
            if (t.getKey().endsWith(".sh"))
                out.add(rec("tool", t.getKey(), t.getValue().path("sha256").asText(), t.getValue().path("url").asText()));
        });
        for (JsonNode v : manifest.path("versions")) {
            JsonNode p = v.path("platforms").path("linux");
            if (p.isMissingNode()) continue;
            String ver = v.path("version").asText();
            out.add(rec("version", ver));
            out.add(rec("dir", ver, p.path("installDir").asText()));
            if (p.has("arch")) out.add(rec("arch", ver, p.path("arch").asText()));
            JsonNode req = p.path("requires");
            if (req.has("java")) out.add(rec("java", ver, req.path("java").asText()));
            req.path("system").fields().forEachRemaining(s -> {
                if (s.getValue().isArray()) for (JsonNode x : s.getValue()) out.add(rec("system", ver, "vulkanExtension", x.asText()));
                else out.add(rec("system", ver, s.getKey(), s.getValue().asText()));
            });
            for (JsonNode n : req.path("installs")) out.add(rec("needs", ver, n.path("id").asText(), n.path("min").asText("")));
            for (JsonNode b : p.path("binaries")) {
                String name = b.path("name").asText();
                out.add(rec("bin", ver, name, b.path("kind").asText(), b.path("version").asText(), b.path("sha256").asText(),
                        b.path("size").asText(), flag(b.path("addToPath")), b.path("file").asText(), b.path("url").asText(),
                        rel(b.path("installPath").asText())));
                if (b.has("icon")) out.add(iconRec("icon", ver, name, b.path("icon")));
                for (JsonNode ti : b.path("fileIcons")) out.add(iconRec("typeicon", ver, name, ti));
                if (b.has("launcher")) out.add(rec("launcher", ver, name, rel(b.path("launcher").asText())));
                if (b.has("runtime")) out.add(rec("runtime", ver, name, b.path("runtime").asText()));
                for (JsonNode a : b.path("jvmArgs")) out.add(rec("jvmarg", ver, name, a.asText()));
                JsonNode sc = b.path("shortcuts");
                if (!sc.isMissingNode()) out.add(rec("shortcut", ver, name, flag(sc.path("desktop")), flag(sc.path("startMenu")),
                        flag(sc.path("gui")), sc.path("label").asText()));
                if (b.has("appId")) out.add(rec("appid", ver, name, b.path("appId").asText()));
                b.path("exposes").fields().forEachRemaining(x -> out.add(rec("expose", ver, name, x.getKey(), x.getValue().asText())));
                for (JsonNode fa : b.path("fileAssociations"))
                    out.add(rec("assoc", ver, name, fa.path("extension").asText(), fa.path("mimeType").asText(),
                            flag(fa.path("ownType")), fa.path("icon").asText(""), fa.path("description").asText()));
            }
        }
        return Map.of(MANIFEST, String.join("\n", out) + "\n");
    }

    private static String iconRec(String kind, String ver, String bin, JsonNode ic) {
        return rec(kind, ver, bin, ic.path("sha256").asText(), ic.path("pixels").asText(), ic.path("file").asText(),
                ic.path("url").asText(), rel(ic.path("installPath").asText()));
    }

    private static String flag(JsonNode n) { return n.asBoolean(false) ? "1" : "0"; }

    /** {@code {installDir}/a/b} as {@code a/b}; the folder itself as {@code .}. */
    private static String rel(String installPath) {
        String r = installPath.replaceFirst("^\\{installDir}/?", "");
        return r.isEmpty() ? "." : r;
    }

    private static String rec(String... fields) {
        StringBuilder b = new StringBuilder();
        for (String f : fields) {
            if (!b.isEmpty()) b.append('\t');
            b.append(f == null || f.isEmpty() ? "-" : f.replaceAll("[\\t\\r\\n]", " "));
        }
        return b.toString();
    }

    // --- README -----------------------------------------------------------------------------------------

    @Override public String installCommand(String installerUrl) { return "curl -fsSL " + installerUrl + " | sh"; }

    @Override
    public String readmeSection(ReadmeContext rc) {
        InstallerConfig c = rc.config();
        PlatformConfig p = rc.platform();
        List<ResolvedBinary> bins = rc.binaries();
        boolean menu = false, desktop = false, onPath = false;
        int javaMin = -1;
        List<String> extensions = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        for (ResolvedBinary b : bins) {
            BinaryConfig bc = b.config();
            if (bc.fileAssociations != null)
                for (FileAssociation fa : bc.fileAssociations) extensions.add("`" + InstallerGenerator.normalizeExtension(fa.extension) + "`");
            if (bc.shortcuts != null) {
                menu |= Boolean.TRUE.equals(bc.shortcuts.startMenu);
                desktop |= bc.shortcuts.desktop == null || bc.shortcuts.desktop;
            }
            if (Boolean.TRUE.equals(bc.addToPath)) { onPath = true; commands.add("`" + b.name() + "`"); }
            if (b.isJar() && InstallerConfig.blank(bc.runtime))
                javaMin = Math.max(javaMin, bc.javaMin == null ? 0 : bc.javaMin);
        }
        String installDir = installDir(c, p);

        List<String> what = new ArrayList<>();
        what.add("- creates the install folder and places the binaries there, executable");
        if (menu) what.add("- adds " + c.name + " to your applications menu");
        if (desktop) what.add("- puts a launcher on your desktop, if you have a Desktop folder");
        if (onPath) what.add("- links " + String.join(", ", commands) + " into `~/.local/bin`, so " + (commands.size() == 1 ? "it runs" : "they run")
                + " from a terminal (a link is never put over a file that is not its own)");
        if (!extensions.isEmpty()) what.add("- registers itself to open " + String.join(", ", extensions)
                + " files (under Open With; the default only where no other program is)");
        what.add("- writes an install manifest listing everything it installed and where");
        what.add("- adds `uninstall.sh` and `update.sh` next to the binaries");

        List<String> table = new ArrayList<>();
        table.add("| File | Kind | Version | Size | Installed to |");
        table.add("| --- | --- | --- | --- | --- |");
        String shown = displayPath(installDir);
        for (ResolvedBinary b : bins) {
            String sub = sub(b.config());
            String kindText = b.isJar() ? "Java uberjar" : b.isArchive() ? "archive (unpacked)" : b.isFile() ? "file" : "native executable";
            String where = shown + (sub.isEmpty() ? "" : "/" + sub) + (b.isArchive() ? "/" : "/" + b.file());
            table.add("| `" + b.file() + "` | " + kindText + " | " + b.version() + " | "
                    + String.format(Locale.ROOT, "%.1f", b.size() / 1048576.0) + " MB | `" + where + "` |");
        }

        String javaNote = "";
        if (javaMin >= 0) {
            javaNote = "\nJava" + (javaMin > 0 ? " " + javaMin + " or newer" : "")
                    + " must be installed and on your PATH (or set `JAVA_HOME`). Each jar gets a launcher script named after it.\n";
        }
        List<String> needs = new ArrayList<>();
        String arch = arch(p, bins);
        if (arch != null) needs.add("- a " + arch + " processor (`uname -m` says `" + arch + "`)");
        if (p.system != null && !p.system.isEmpty()) p.system.describe().forEach(l -> needs.add("- " + l));
        String requirements = needs.isEmpty() ? "" : "\n### System requirements\n\n" + String.join("\n", needs)
                + "\n\nThe installer checks these before it downloads anything large and stops if one is not met"
                + (p.system != null && (!InstallerConfig.blank(p.system.vulkan) || p.system.vulkanExtensions != null && !p.system.vulkanExtensions.isEmpty())
                        ? " (the GPU through `vulkaninfo`, from the `vulkan-tools` package; without it the GPU is not checked)" : "")
                + ". To install anyway, run it as `curl -fsSL " + rc.installerUrl() + " | INSTALL_SKIP_CHECKS=1 sh`.\n";
        String issues = c.issuesUrlOrDefault();

        return Templates.render("linux/README.section.md", Map.ofEntries(
                Map.entry("REQUIREMENTS", requirements),
                Map.entry("ISSUES", issues == null ? "" : " Report problems at " + issues + "."),
                Map.entry("NAME", c.name),
                Map.entry("VERSION", rc.version()),
                Map.entry("INSTALL_COMMAND", installCommand(rc.installerUrl())),
                Map.entry("INSTALL_URL", rc.installerUrl()),
                Map.entry("MANIFEST_URL", rc.manifestUrl()),
                Map.entry("WHAT_IT_DOES", String.join("\n", what)),
                Map.entry("BINARY_TABLE", String.join("\n", table)),
                Map.entry("JAVA_NOTE", javaNote),
                Map.entry("INSTALL_DIR", shown),
                Map.entry("SH_INSTALL_DIR", shellPath(installDir)),
                Map.entry("REGISTRY_DIR", displayPath(registryDir(p))),
                Map.entry("ID", c.id)));
    }
}
