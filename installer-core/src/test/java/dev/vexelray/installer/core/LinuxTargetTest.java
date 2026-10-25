package dev.vexelray.installer.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LinuxTargetTest {
    @TempDir Path tmp;
    Path out, assets, src;

    @BeforeEach
    void setUp() throws IOException {
        out = tmp.resolve("out");
        assets = tmp.resolve("assets");
        src = tmp.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("app"), "elf-v1");
        Files.writeString(src.resolve("tool-uber.jar"), "jar-v1");
        tarGz(src.resolve("rt.tar.gz"), Map.of("./bin/java", "java", "lib/modules", "m"));
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(img, "png", src.resolve("icon.png").toFile());
    }

    /** A gzipped ustar with these entries: enough of a tarball for the generator to read. */
    static void tarGz(Path dest, Map<String, String> entries) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (var e : entries.entrySet()) {
            byte[] data = e.getValue().getBytes(StandardCharsets.UTF_8);
            byte[] h = new byte[512];
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            System.arraycopy(name, 0, h, 0, name.length);
            put(h, 100, "0000755");
            put(h, 124, String.format("%011o", data.length));
            h[156] = '0';
            put(h, 257, "ustar");
            tar.write(h);
            tar.write(data);
            tar.write(new byte[(512 - data.length % 512) % 512]);
        }
        tar.write(new byte[1024]);
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(dest))) { o.write(tar.toByteArray()); }
    }

    private static void put(byte[] h, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, b.length);
    }

    InstallerConfig config() {
        InstallerConfig c = new InstallerConfig();
        c.id = "myapp";
        c.name = "MyApp";
        c.description = "Does things.";
        c.repo = "owner/myapp";
        c.linux = new PlatformConfig();
        BinaryConfig app = new BinaryConfig();
        app.source = "src/app";
        app.addToPath = true;
        app.icon = "src/icon.png";
        app.shortcuts = new ShortcutConfig();
        app.shortcuts.label = "MyApp";
        app.shortcuts.startMenu = true;
        app.appId = "dev.example.myapp";
        FileAssociation fa = new FileAssociation();
        fa.extension = "VXR";
        app.fileAssociations = List.of(fa);
        BinaryConfig jar = new BinaryConfig();
        jar.source = "src/tool-uber.jar";
        jar.file = "tool.jar";
        jar.javaMin = 21;
        jar.jvmArgs = List.of("-Xmx1g");
        BinaryConfig rt = new BinaryConfig();
        rt.source = "src/rt.tar.gz";
        rt.dir = "rt";
        rt.exposes = Map.of("java", "bin/java");
        c.linux.binaries.addAll(List.of(app, jar, rt));
        return c;
    }

    GenerationResult run(InstallerConfig c, String version) {
        GenerationRequest r = new GenerationRequest();
        r.config = c;
        r.version = version;
        r.outDir = out;
        r.assetsDir = assets;
        r.baseDir = tmp;
        r.clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
        return new InstallerGenerator().generate(r);
    }

    String read(String file) throws IOException { return Files.readString(out.resolve(file), StandardCharsets.UTF_8); }

    @Test
    void writesShellScriptsTheirManifestAndAReadme() throws IOException {
        GenerationResult res = run(config(), "1.0.0");

        assertEquals("curl -fsSL https://raw.githubusercontent.com/owner/myapp/main/install.sh | sh", res.installCommand());
        for (String s : new String[] {"install.sh", "uninstall.sh", "update.sh"}) {
            String text = read(s);
            assertTrue(text.startsWith("#!/bin/sh\n"), s);
            assertTrue(text.chars().allMatch(ch -> ch < 0x80), s + " is ASCII");
            assertFalse(text.contains("\r"), s + " has LF endings");
            assertTrue(text.contains("VI_MANIFEST_URL='https://raw.githubusercontent.com/owner/myapp/main/manifest.linux.txt'"), s);
            assertTrue(text.contains("VI_REGISTRY_DIR='$XDG_DATA_HOME/vexelray-installer/installs'"), s);
            assertFalse(text.matches("(?s).*\\{\\{[A-Z_]+}}.*"), s + " has no tokens left");
        }

        JsonNode linux = Json.tree(read("manifest.json")).path("versions").get(0).path("platforms").path("linux");
        assertEquals("$HOME/.local/opt/myapp", linux.path("installDir").asText());
        assertEquals("x86_64", linux.path("arch").asText());
        assertEquals(21, linux.path("requires").path("java").asInt());
        JsonNode app = linux.path("binaries").get(0);
        assertEquals("{installDir}/app", app.path("installPath").asText());
        assertEquals("app.png", app.path("icon").path("file").asText());
        assertEquals(64, app.path("icon").path("pixels").asInt());
        assertEquals("application/x-myapp-vxr", app.path("fileAssociations").get(0).path("mimeType").asText());
        assertTrue(app.path("fileAssociations").get(0).path("ownType").asBoolean());
        assertTrue(app.path("shortcuts").path("gui").asBoolean(), "a menu entry opens no terminal unless asked to");
        assertEquals("dev.example.myapp", app.path("appId").asText());
        assertEquals("{installDir}/tool", linux.path("binaries").get(1).path("launcher").asText());
        assertEquals("{installDir}/rt", linux.path("binaries").get(2).path("installPath").asText());
        assertTrue(Files.exists(assets.resolve("1.0.0/app.png")), "the icon ships as a PNG");

        String txt = read("manifest.linux.txt");
        List<String> lines = txt.lines().toList();
        assertEquals("vexelray-installer-linux\t1", lines.get(0));
        assertTrue(lines.contains("latest\t1.0.0"));
        assertTrue(lines.contains("dir\t1.0.0\t$HOME/.local/opt/myapp"));
        assertTrue(lines.stream().anyMatch(l -> l.matches("tool\tinstall\\.sh\t[0-9a-f]{64}\thttps://\\S+/install\\.sh")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("bin\t1.0.0\tapp\tnative\t1.0.0\t") && l.endsWith("\t1\tapp\thttps://github.com/owner/myapp/releases/download/v1.0.0/app\tapp")));
        assertTrue(lines.contains("launcher\t1.0.0\ttool\ttool"));
        assertTrue(lines.contains("jvmarg\t1.0.0\ttool\t-Xmx1g"));
        assertTrue(lines.contains("expose\t1.0.0\trt\tjava\tbin/java"), txt);
        assertTrue(lines.contains("shortcut\t1.0.0\tapp\t1\t1\t1\tMyApp"));
        assertTrue(lines.contains("appid\t1.0.0\tapp\tdev.example.myapp"), txt);
        assertTrue(lines.contains("assoc\t1.0.0\tapp\t.vxr\tapplication/x-myapp-vxr\t1\t-\tMyApp VXR file"));
        for (String l : lines) assertFalse(l.contains("\t\t") || l.endsWith("\t"), "no empty field: " + l);

        String readme = read("README.md");
        assertTrue(readme.contains("## Install (Linux)"));
        assertTrue(readme.contains("curl -fsSL https://raw.githubusercontent.com/owner/myapp/main/install.sh | sh"));
        assertTrue(readme.contains("`~/.local/opt/myapp/rt/`"));
        assertTrue(readme.contains("\"$HOME/.local/opt/myapp/uninstall.sh\""));
        assertTrue(readme.contains("A macOS installer is planned"));
    }

    @Test
    void aRenamedApplicationNamesWhatItReplacesAndForwardsItsOldFolder() throws IOException {
        InstallerConfig c = config();
        ReplacedInstall was = new ReplacedInstall();
        was.id = "oldapp";
        was.repoPath = "apps/oldapp/";
        c.replaces = List.of(was);
        run(c, "1.0.0");
        assertEquals("oldapp", Json.tree(read("manifest.json")).path("replaces").get(0).asText());
        assertTrue(read("manifest.linux.txt").lines().toList().contains("replaces\toldapp"));
        // no repoPath of its own: the output folder is the repo's root, and the old folder is inside it
        Path forward = out.resolve("apps/oldapp");
        for (String f : List.of("manifest.json", "manifest.linux.txt", "install.sh"))
            assertEquals(read(f), Files.readString(forward.resolve(f), StandardCharsets.UTF_8), f + " is forwarded as it is");
        assertTrue(Files.readString(forward.resolve("README.md"), StandardCharsets.UTF_8).contains("renamed"));

        InstallerConfig self = config();
        ReplacedInstall same = new ReplacedInstall();
        same.id = "myapp";
        self.replaces = List.of(same);
        assertTrue(assertThrows(InstallerException.class, () -> run(self, "1.0.0")).getMessage().contains("own id"));
    }

    @Test
    void theOldFolderIsBesideTheNewOneInACheckoutOfTheRepo() {
        assertEquals(tmp.resolve("site/apps/old"), InstallerGenerator.forwardDir(tmp.resolve("site/apps/new"), "apps/new", "apps/old"));
        assertEquals(tmp.resolve("out/forward/apps/old"), InstallerGenerator.forwardDir(tmp.resolve("out"), "apps/new", "apps/old"));
        assertEquals(tmp.resolve("out/apps/old"), InstallerGenerator.forwardDir(tmp.resolve("out"), "", "apps/old"));
    }

    @Test
    void theScriptsManifestKeepsEveryVersionAndNamesTheNewestRelease() throws IOException {
        run(config(), "1.0.0");
        run(config(), "1.1.0-rc1");
        String txt = read("manifest.linux.txt");
        assertTrue(txt.contains("\nlatest\t1.0.0\n"), "a pre-release is not the latest");
        assertTrue(txt.contains("\nversion\t1.1.0-rc1\n") && txt.contains("\nversion\t1.0.0\n"));
    }

    @Test
    void windowsAndLinuxTogether() throws IOException {
        InstallerConfig c = config();
        c.windows = new PlatformConfig();
        BinaryConfig exe = new BinaryConfig();
        Files.writeString(src.resolve("app.exe"), "exe");
        exe.source = "src/app.exe";
        exe.icon = "src/icon.png";
        c.windows.binaries.add(exe);
        GenerationResult res = run(c, "1.0.0");

        assertTrue(res.installCommand().startsWith("irm "), "Windows comes first");
        assertTrue(Files.exists(out.resolve("install.ps1")) && Files.exists(out.resolve("install.sh")));
        JsonNode v = Json.tree(read("manifest.json")).path("versions").get(0);
        assertTrue(v.path("platforms").has("windows") && v.path("platforms").has("linux"));
        assertTrue(v.path("installer").has("install.ps1") && v.path("installer").has("install.sh"));
        assertTrue(Files.exists(assets.resolve("1.0.0/app.ico")) && Files.exists(assets.resolve("1.0.0/app.png")));
        String readme = read("README.md");
        assertTrue(readme.indexOf("## Install (Windows)") < readme.indexOf("## Install (Linux)"));
    }

    @Test
    void onlyThePlatformsAskedForNeedTheirArtifacts() throws IOException {
        InstallerConfig c = config();
        c.windows = new PlatformConfig();
        BinaryConfig exe = new BinaryConfig();
        exe.source = "src/missing.exe";
        c.windows.binaries.add(exe);
        assertThrows(InstallerException.class, () -> run(c, "1.0.0"), "every platform by default");

        GenerationRequest r = new GenerationRequest();
        r.config = c;
        r.version = "1.0.0";
        r.outDir = out;
        r.assetsDir = assets;
        r.baseDir = tmp;
        new InstallerGenerator(Main.targets("linux")).generate(r);
        assertTrue(Files.exists(out.resolve("install.sh")));
        assertFalse(Files.exists(out.resolve("install.ps1")));
        assertFalse(Json.tree(read("manifest.json")).path("versions").get(0).path("platforms").has("windows"));
        assertEquals(List.of("linux", "windows"), Main.targets("linux, windows,linux").stream().map(PlatformTarget::key).toList());
        assertThrows(InstallerException.class, () -> Main.targets("macos"));
    }

    InstallerConfig both() throws IOException {
        InstallerConfig c = config();
        c.windows = new PlatformConfig();
        BinaryConfig exe = new BinaryConfig();
        Files.writeString(src.resolve("app.exe"), "exe");
        exe.source = "src/app.exe";
        exe.icon = "src/icon.png";
        c.windows.binaries.add(exe);
        return c;
    }

    void runOnly(InstallerConfig c, String version, String platform, String day) {
        GenerationRequest r = new GenerationRequest();
        r.config = c;
        r.version = version;
        r.outDir = out;
        r.assetsDir = assets;
        r.baseDir = tmp;
        r.clock = Clock.fixed(Instant.parse(day + "T00:00:00Z"), ZoneOffset.UTC);
        new InstallerGenerator(Main.targets(platform)).generate(r);
    }

    @Test
    void eachPlatformPublishesIntoTheReleaseOnItsOwn() throws IOException {
        InstallerConfig c = both();
        run(c, "1.0.0");
        JsonNode before = Json.tree(read("manifest.json"));

        runOnly(c, "1.1.0", "windows", "2026-10-22");
        JsonNode m = Json.tree(read("manifest.json"));
        JsonNode v = m.path("versions").get(0);
        assertEquals("1.1.0", v.path("version").asText());
        assertFalse(v.path("platforms").has("linux"), "Linux has not published 1.1.0");
        assertEquals("1.1.0", m.path("latest").asText(), "Windows installs the version it has");
        assertTrue(read("manifest.linux.txt").contains("\nlatest\t1.0.0\n"), "Linux keeps installing the version it has");
        assertEquals(before.path("tools").path("install.sh"), m.path("tools").path("install.sh"), "the Linux scripts are kept");
        String readme = read("README.md");
        assertTrue(readme.contains("## Install (Windows)") && readme.contains("## Install (Linux)"), readme);

        runOnly(c, "1.1.0", "linux", "2026-10-23");
        m = Json.tree(read("manifest.json"));
        v = m.path("versions").get(0);
        assertTrue(v.path("platforms").has("windows") && v.path("platforms").has("linux"), "the Windows build is kept");
        assertTrue(v.path("installer").has("install.ps1") && v.path("installer").has("install.sh"));
        assertEquals("2026-10-22T00:00:00Z", v.path("publishedAt").asText());
        assertEquals("2026-10-23T00:00:00Z", v.path("updatedAt").asText());
        assertEquals(List.of("1.1.0", "1.0.0"), m.path("versions").findValuesAsText("version").stream().distinct().toList());
        assertTrue(read("manifest.linux.txt").contains("\nlatest\t1.1.0\n"));
    }

    @Test
    void aPlatformBehindKeepsItsReadmeSectionAfterTheConfigGrew() throws IOException {
        InstallerConfig c = both();
        run(c, "1.0.0");
        Files.writeString(src.resolve("extra.tar.gz"), "");
        tarGz(src.resolve("extra.tar.gz"), Map.of("x", "x"));
        BinaryConfig extra = new BinaryConfig();
        extra.source = "src/extra.tar.gz";
        extra.dir = "extra";
        c.linux.binaries.add(extra);   // named by the config now, but not in Linux's 1.0.0
        runOnly(c, "1.1.0", "windows", "2026-10-22");
        String readme = read("README.md");
        assertTrue(readme.contains("## Install (Linux)"), "Linux's section, of the 1.0.0 it has: " + readme);
        assertFalse(readme.contains("~/.local/opt/myapp/extra/"), "and of what 1.0.0 shipped, not the config's newer set");
    }

    @Test
    void aLaterPlatformFirstLeavesTheWindowsLatestAlone() throws IOException {
        InstallerConfig c = both();
        run(c, "1.0.0");
        runOnly(c, "1.1.0", "linux", "2026-10-22");
        assertEquals("1.0.0", Json.tree(read("manifest.json")).path("latest").asText(),
                "Windows updaters read latest, and 1.1.0 has no Windows build yet");
        assertTrue(read("manifest.linux.txt").contains("\nlatest\t1.1.0\n"));
    }

    @Test
    void aFileBothPlatformsShipMustBeTheSameUpload() throws IOException {
        InstallerConfig c = both();
        Files.writeString(src.resolve("NOTICES.txt"), "notices as Windows built them");
        BinaryConfig notices = new BinaryConfig();
        notices.source = "src/NOTICES.txt";
        c.windows.binaries.add(notices);
        c.linux.binaries.add(notices);
        runOnly(c, "1.1.0", "windows", "2026-10-22");
        runOnly(c, "1.1.0", "linux", "2026-10-23");   // the same bytes: one upload serves both

        Files.writeString(src.resolve("NOTICES.txt"), "notices as Linux built them");
        String why = assertThrows(InstallerException.class, () -> runOnly(c, "1.1.0", "linux", "2026-10-24")).getMessage();
        assertTrue(why.contains("NOTICES.txt") && why.contains("another platform"), why);
    }

    @Test
    void refusesWhatLinuxCannotInstall() throws IOException {
        Files.writeString(src.resolve("rt.zip"), "zip");
        InstallerConfig zip = config();
        zip.linux.binaries.get(2).source = "src/rt.zip";
        zip.linux.binaries.get(2).exposes = null;
        assertThrows(InstallerException.class, () -> run(zip, "1.0.0"), "a zip archive");

        InstallerConfig signed = config();
        signed.linux.binaries.get(0).signedBy = "CN=Example";
        assertTrue(assertThrows(InstallerException.class, () -> run(signed, "1.0.0")).getMessage().contains("Authenticode"));

        InstallerConfig d3d = config();
        d3d.linux.system = new SystemRequirements();
        d3d.linux.system.d3d12 = "12_0";
        assertThrows(InstallerException.class, () -> run(d3d, "1.0.0"));

        InstallerConfig tab = config();
        tab.linux.binaries.get(0).shortcuts.label = "My\tApp";
        assertThrows(InstallerException.class, () -> run(tab, "1.0.0"));

        InstallerConfig oneWord = config();
        oneWord.linux.binaries.get(0).appId = "myapp";
        assertTrue(assertThrows(InstallerException.class, () -> run(oneWord, "1.0.0")).getMessage().contains("appId"));

        InstallerConfig placed = config();
        placed.linux.binaries.get(2).appId = "dev.example.rt";
        assertTrue(assertThrows(InstallerException.class, () -> run(placed, "1.0.0")).getMessage().contains("appId"));

        InstallerConfig clash = config();
        Files.writeString(src.resolve("tool"), "x");
        BinaryConfig other = new BinaryConfig();
        other.source = "src/tool";
        clash.linux.binaries.add(other);
        assertTrue(assertThrows(InstallerException.class, () -> run(clash, "1.0.0")).getMessage().contains("launcher"));

        InstallerConfig missing = config();
        missing.linux.binaries.get(2).exposes = Map.of("java", "bin/javac");
        assertTrue(assertThrows(InstallerException.class, () -> run(missing, "1.0.0")).getMessage().contains("not in rt.tar.gz"));

        InstallerConfig relative = config();
        relative.linux.installDir = "opt/myapp";
        assertThrows(InstallerException.class, () -> run(relative, "1.0.0"));
    }

    @Test
    void refusesATarballThatUnpacksOutsideItsFolder() throws IOException {
        tarGz(src.resolve("rt.tar.gz"), Map.of("../evil", "x"));
        InstallerConfig c = config();
        c.linux.binaries.get(2).exposes = null;
        assertTrue(assertThrows(InstallerException.class, () -> run(c, "1.0.0")).getMessage().contains("outside"));
    }

    @Test
    void windowsRefusesATarballAndLinuxOnlySettings() throws IOException {
        InstallerConfig c = new InstallerConfig();
        c.id = "w";
        c.name = "W";
        c.repo = "owner/w";
        c.windows = new PlatformConfig();
        BinaryConfig b = new BinaryConfig();
        b.source = "src/rt.tar.gz";
        c.windows.binaries.add(b);
        assertTrue(assertThrows(InstallerException.class, () -> run(c, "1.0.0")).getMessage().contains(".zip"));
        c.windows.binaries.get(0).source = "src/app";
        c.windows.arch = "x86_64";
        assertThrows(InstallerException.class, () -> run(c, "1.0.0"));
    }

    @Test
    void quotesForShellAsciiOrNot() {
        assertEquals("'it'\\''s'", LinuxTarget.shQuote("it's"));
        assertEquals("\"$(printf 'Caf\\303\\251')\"", LinuxTarget.shQuote("Café"));
    }

    @Test
    void pathsForPeopleAndForShells() {
        assertEquals("~/.local/share/vexelray-installer/installs", LinuxTarget.displayPath(LinuxTarget.DEFAULT_REGISTRY_DIR));
        assertEquals("${XDG_DATA_HOME:-$HOME/.local/share}/x", LinuxTarget.shellPath("$XDG_DATA_HOME/x"));
        assertEquals("$HOME/opt/x", LinuxTarget.shellPath("~/opt/x"));
    }
}
