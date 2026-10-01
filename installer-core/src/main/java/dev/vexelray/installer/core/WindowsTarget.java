package dev.vexelray.installer.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Per-user Windows install driven by PowerShell scripts ({@code irm | iex}). */
public final class WindowsTarget implements PlatformTarget {
    static final String DEFAULT_INSTALL_DIR = "%LOCALAPPDATA%\\Programs\\{name}";
    static final String DEFAULT_REGISTRY_DIR = "%LOCALAPPDATA%\\vexelray-installer\\installs";

    @Override public String key() { return "windows"; }

    @Override public PlatformConfig configOf(InstallerConfig config) { return config.windows; }

    @Override public List<String> scriptNames() { return List.of("install.ps1", "uninstall.ps1", "update.ps1"); }

    @Override
    public ObjectNode scriptConfig(InstallerConfig c, PlatformConfig p, String manifestUrl) {
        ObjectNode n = Json.object();
        n.put("id", c.id);
        n.put("name", c.name);
        n.put("publisher", c.publisherOrName());
        n.put("manifestUrl", manifestUrl);
        n.put("registryDir", registryDir(p));
        return n;
    }

    private static String registryDir(PlatformConfig p) {
        return InstallerConfig.blank(p.registryDir) ? DEFAULT_REGISTRY_DIR : p.registryDir;
    }

    private static String installDir(InstallerConfig c, PlatformConfig p) {
        return (InstallerConfig.blank(p.installDir) ? DEFAULT_INSTALL_DIR : p.installDir).replace("{name}", c.name);
    }

    @Override
    public ObjectNode manifestEntry(InstallerConfig c, PlatformConfig p, List<ResolvedBinary> bins) {
        ObjectNode platform = Json.object();
        platform.put("installDir", installDir(c, p));

        int javaMin = -1;
        ArrayNode binaries = Json.NODES.arrayNode();
        for (ResolvedBinary b : bins) {
            BinaryConfig bc = b.config();
            String sub = bc.dir == null ? "" : bc.dir.replaceAll("^[/\\\\]+|[/\\\\]+$", "");
            String rel = sub.isEmpty() ? b.file() : sub + "\\" + b.file();

            ObjectNode e = Json.object();
            e.put("name", b.name());
            e.put("version", b.version());
            e.put("kind", b.kind());
            e.put("file", b.file());
            e.put("url", b.url());
            e.put("sha256", b.sha256());
            e.put("size", b.size());
            e.put("installPath", "{installDir}\\" + rel);
            e.put("executable", true);
            e.put("addToPath", Boolean.TRUE.equals(bc.addToPath));
            if (b.isJar()) {
                String dir = ("{installDir}\\" + sub).replaceAll("\\\\+$", "");
                e.put("launcher", dir + "\\" + b.name() + ".cmd");
                javaMin = Math.max(javaMin, bc.javaMin == null ? 0 : bc.javaMin);
            }
            ShortcutConfig sc = bc.shortcuts;
            if (sc != null) {
                ObjectNode s = e.putObject("shortcuts");
                s.put("desktop", sc.desktop == null || sc.desktop);
                s.put("startMenu", Boolean.TRUE.equals(sc.startMenu));
                s.put("label", InstallerConfig.blank(sc.label) ? b.name() : sc.label);
                s.put("gui", sc.gui == null ? b.isJar() : sc.gui);
            }
            binaries.add(e);
        }
        if (javaMin >= 0) platform.putObject("requires").put("java", javaMin);
        platform.set("binaries", binaries);
        return platform;
    }

    @Override public String installCommand(String installerUrl) { return "irm " + installerUrl + " | iex"; }

    @Override
    public String readmeSection(ReadmeContext rc) {
        InstallerConfig c = rc.config();
        PlatformConfig p = rc.platform();
        List<ResolvedBinary> bins = rc.binaries();
        boolean shortcuts = false, onPath = false;
        int javaMin = -1;
        for (ResolvedBinary b : bins) {
            shortcuts |= b.config().shortcuts != null;
            onPath |= Boolean.TRUE.equals(b.config().addToPath);
            if (b.isJar()) javaMin = Math.max(javaMin, b.config().javaMin == null ? 0 : b.config().javaMin);
        }
        String installDir = installDir(c, p);

        List<String> what = new ArrayList<>();
        what.add("- creates the install folder and places the binaries there");
        what.add("- unblocks them and makes sure they are executable");
        if (shortcuts) what.add("- creates desktop / Start menu shortcuts");
        if (onPath) what.add("- adds the install folder to your user PATH");
        what.add("- writes an install manifest listing everything it installed and where");
        what.add("- adds an uninstaller and an updater next to the binaries, and an entry in Settings > Apps");

        List<String> table = new ArrayList<>();
        table.add("| File | Kind | Version | Size | Installed to |");
        table.add("| --- | --- | --- | --- | --- |");
        for (ResolvedBinary b : bins) {
            String sub = b.config().dir == null ? "" : b.config().dir.replaceAll("^[/\\\\]+|[/\\\\]+$", "");
            String rel = sub.isEmpty() ? b.file() : sub + "\\" + b.file();
            table.add("| `" + b.file() + "` | " + (b.isJar() ? "Java uberjar" : "native executable") + " | "
                    + b.version() + " | " + String.format(Locale.ROOT, "%.1f", b.size() / 1048576.0) + " MB | `"
                    + installDir + "\\" + rel + "` |");
        }

        String javaNote = "";
        if (javaMin >= 0) {
            javaNote = "\nJava" + (javaMin > 0 ? " " + javaMin + " or newer" : "")
                    + " must be installed and on your PATH (or set `JAVA_HOME`). A `<name>.cmd` launcher is created for each jar.\n";
        }

        return Templates.render("windows/README.section.md", Map.ofEntries(
                Map.entry("NAME", c.name),
                Map.entry("VERSION", rc.version()),
                Map.entry("INSTALL_COMMAND", installCommand(rc.installerUrl())),
                Map.entry("INSTALL_URL", rc.installerUrl()),
                Map.entry("MANIFEST_URL", rc.manifestUrl()),
                Map.entry("WHAT_IT_DOES", String.join("\n", what)),
                Map.entry("BINARY_TABLE", String.join("\n", table)),
                Map.entry("JAVA_NOTE", javaNote),
                Map.entry("INSTALL_DIR", installDir),
                Map.entry("REGISTRY_DIR", registryDir(p)),
                Map.entry("ID", c.id)));
    }
}
