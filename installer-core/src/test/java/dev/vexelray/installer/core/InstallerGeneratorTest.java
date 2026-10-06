package dev.vexelray.installer.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class InstallerGeneratorTest {
    @TempDir Path tmp;
    Path out, assets, src;

    @BeforeEach
    void setUp() throws IOException {
        out = tmp.resolve("out");
        assets = tmp.resolve("assets");
        src = tmp.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("app.exe"), "exe-v1");
        Files.writeString(src.resolve("tool-uber.jar"), "jar-v1");
    }

    InstallerConfig config() {
        InstallerConfig c = new InstallerConfig();
        c.id = "myapp";
        c.name = "MyApp";
        c.description = "Does things.";
        c.repo = "owner/myapp";
        c.windows = new PlatformConfig();
        BinaryConfig exe = new BinaryConfig();
        exe.source = "src/app.exe";
        exe.addToPath = true;
        exe.shortcuts = new ShortcutConfig();
        exe.shortcuts.label = "MyApp";
        BinaryConfig jar = new BinaryConfig();
        jar.source = "src/tool-uber.jar";
        jar.file = "tool.jar";
        jar.javaMin = 21;
        c.windows.binaries.add(exe);
        c.windows.binaries.add(jar);
        return c;
    }

    GenerationResult run(String version, String at) {
        GenerationRequest r = new GenerationRequest();
        r.config = config();
        r.version = version;
        r.outDir = out;
        r.assetsDir = assets;
        r.baseDir = tmp;
        r.notes = "notes " + version;
        r.clock = Clock.fixed(Instant.parse(at), ZoneOffset.UTC);
        return new InstallerGenerator().generate(r);
    }

    JsonNode manifest() throws IOException {
        return Json.tree(Files.readString(out.resolve("manifest.json")));
    }

    @Test
    void writesScriptsManifestAndReadme() throws IOException {
        GenerationResult res = run("1.0.0", "2026-01-01T00:00:00Z");

        assertEquals("irm https://raw.githubusercontent.com/owner/myapp/main/install.ps1 | iex", res.installCommand());
        for (String f : new String[] {"install.ps1", "uninstall.ps1", "update.ps1", "manifest.json", "README.md"})
            assertTrue(Files.exists(out.resolve(f)), f);

        JsonNode m = manifest();
        assertEquals("1.0.0", m.path("latest").asText());
        JsonNode bins = m.path("versions").get(0).path("platforms").path("windows").path("binaries");
        assertEquals("app.exe", bins.get(0).path("file").asText());
        assertEquals("https://github.com/owner/myapp/releases/download/v1.0.0/app.exe", bins.get(0).path("url").asText());
        assertEquals(InstallerGenerator.sha256(assets.resolve("1.0.0/app.exe")), bins.get(0).path("sha256").asText());
        assertEquals("{installDir}\\tool.cmd", bins.get(1).path("launcher").asText());
        assertEquals(21, m.path("versions").get(0).path("platforms").path("windows").path("requires").path("java").asInt());
        assertTrue(bins.get(0).path("shortcuts").path("desktop").asBoolean());
        assertTrue(bins.get(1).path("shortcuts").isMissingNode());

        String readme = Files.readString(out.resolve("README.md"));
        assertTrue(readme.contains(res.installCommand()));
        assertTrue(readme.contains("Java 21 or newer"));
        assertFalse(readme.contains("{{"));
    }

    @Test
    void manifestHashesMatchTheScriptsOnDisk() throws IOException {
        run("1.0.0", "2026-01-01T00:00:00Z");
        JsonNode m = manifest();
        for (String s : new String[] {"install.ps1", "uninstall.ps1", "update.ps1"}) {
            assertEquals(InstallerGenerator.sha256(out.resolve(s)), m.path("tools").path(s).path("sha256").asText(), s);
            assertEquals(m.path("tools").path(s).path("sha256").asText(),
                    m.path("versions").get(0).path("installer").path(s).asText());
        }
    }

    @Test
    void scriptsAreAsciiWithLfAndEmbedTheirConfig() throws IOException {
        run("1.0.0", "2026-01-01T00:00:00Z");
        for (String s : new String[] {"install.ps1", "uninstall.ps1", "update.ps1"}) {
            byte[] bytes = Files.readAllBytes(out.resolve(s));
            for (byte b : bytes) assertTrue(b >= 0 && b != '\r', s + " has a non-ASCII byte or CR");
            String text = new String(bytes, StandardCharsets.US_ASCII);
            assertTrue(text.contains("\"manifestUrl\":\"https://raw.githubusercontent.com/owner/myapp/main/manifest.json\""), s);
            assertFalse(text.contains("{{"), s);
        }
    }

    @Test
    void historyKeepsEveryVersionNewestFirst() throws IOException {
        run("1.0.0", "2026-01-01T00:00:00Z");
        run("1.1.0", "2026-02-01T00:00:00Z");
        run("1.0.1", "2026-03-01T00:00:00Z"); // a hotfix published later must not become "latest"

        JsonNode m = manifest();
        assertEquals("1.1.0", m.path("latest").asText());
        assertEquals(3, m.path("versions").size());
        assertEquals("1.1.0", m.path("versions").get(0).path("version").asText());
        assertEquals("1.0.1", m.path("versions").get(1).path("version").asText());
        assertEquals("1.0.0", m.path("versions").get(2).path("version").asText());
        assertEquals("2026-01-01T00:00:00Z", m.path("versions").get(2).path("publishedAt").asText());
    }

    @Test
    void republishingReplacesTheEntryAndKeepsItsOriginalDate() throws IOException {
        run("1.0.0", "2026-01-01T00:00:00Z");
        Files.writeString(src.resolve("app.exe"), "exe-v1-fixed");
        run("1.0.0", "2026-01-05T00:00:00Z");

        JsonNode m = manifest();
        assertEquals(1, m.path("versions").size());
        JsonNode v = m.path("versions").get(0);
        assertEquals("2026-01-01T00:00:00Z", v.path("publishedAt").asText());
        assertEquals("2026-01-05T00:00:00Z", v.path("updatedAt").asText());
        assertEquals(InstallerGenerator.sha256(src.resolve("app.exe")),
                v.path("platforms").path("windows").path("binaries").get(0).path("sha256").asText());
    }

    @Test
    void prereleaseNeverBecomesLatestWhileAReleaseExists() throws IOException {
        run("1.0.0", "2026-01-01T00:00:00Z");
        GenerationResult res = run("1.1.0-rc1", "2026-02-01T00:00:00Z");
        assertTrue(res.prerelease());
        assertEquals("1.0.0", manifest().path("latest").asText());
    }

    @Test
    void anIconIsConvertedStagedAndListedInTheManifest() throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(img, "png", src.resolve("mark.png").toFile());
        GenerationRequest r = new GenerationRequest();
        r.config = config();
        r.config.windows.binaries.get(1).icon = "src/mark.png";
        r.version = "1.0.0";
        r.outDir = out;
        r.assetsDir = assets;
        r.baseDir = tmp;
        GenerationResult res = new InstallerGenerator().generate(r);

        JsonNode jar = manifest().path("versions").get(0).path("platforms").path("windows").path("binaries").get(1);
        assertEquals("tool.ico", jar.path("icon").path("file").asText());
        assertEquals("{installDir}\\tool.ico", jar.path("icon").path("installPath").asText());
        assertEquals("https://github.com/owner/myapp/releases/download/v1.0.0/tool.ico", jar.path("icon").path("url").asText());
        assertEquals(InstallerGenerator.sha256(assets.resolve("1.0.0/tool.ico")), jar.path("icon").path("sha256").asText());
        assertTrue(res.assets().contains(assets.resolve("1.0.0").resolve("tool.ico")));
        assertTrue(manifest().path("versions").get(0).path("platforms").path("windows").path("binaries").get(0).path("icon").isMissingNode());
    }

    @Test
    void foreignReadmeIsNotOverwritten() throws IOException {
        Files.createDirectories(out);
        Files.writeString(out.resolve("README.md"), "# my own readme\n");
        run("1.0.0", "2026-01-01T00:00:00Z");
        assertEquals("# my own readme\n", Files.readString(out.resolve("README.md")));
        assertTrue(Files.exists(out.resolve("INSTALL.md")));
    }

    @Test
    void refusesAManifestOfAnotherApp() throws IOException {
        run("1.0.0", "2026-01-01T00:00:00Z");
        GenerationRequest r = new GenerationRequest();
        r.config = config();
        r.config.id = "other";
        r.version = "1.0.1";
        r.outDir = out;
        r.assetsDir = assets;
        r.baseDir = tmp;
        assertThrows(InstallerException.class, () -> new InstallerGenerator().generate(r));
    }

    @Test
    void missingArtifactAndBadConfigAreReportedClearly() {
        InstallerException e = assertThrows(InstallerException.class, () -> {
            GenerationRequest r = new GenerationRequest();
            r.config = config();
            r.config.windows.binaries.get(0).source = "src/nope.exe";
            r.version = "1.0.0";
            r.outDir = out;
            r.assetsDir = assets;
            r.baseDir = tmp;
            new InstallerGenerator().generate(r);
        });
        assertTrue(e.getMessage().contains("artifact not found"));

        assertThrows(InstallerException.class, () -> {
            GenerationRequest r = new GenerationRequest();
            r.config = config();
            r.config.id = "Bad Id";
            r.version = "1.0.0";
            r.outDir = out;
            r.assetsDir = assets;
            new InstallerGenerator().generate(r);
        });
    }

    GenerationResult runWith(java.util.function.Consumer<InstallerConfig> tweak) {
        GenerationRequest r = new GenerationRequest();
        r.config = config();
        tweak.accept(r.config);
        r.version = "1.0.0";
        r.outDir = out;
        r.assetsDir = assets;
        r.baseDir = tmp;
        return new InstallerGenerator().generate(r);
    }

    static FileAssociation assoc(String ext) {
        FileAssociation fa = new FileAssociation();
        fa.extension = ext;
        return fa;
    }

    @Test
    void fileAssociationsLandInTheManifestAndReadme() throws IOException {
        runWith(c -> {
            FileAssociation proj = assoc("VXR");
            proj.description = "MyApp Project";
            proj.contentType = "application/x-vexelray";
            c.windows.binaries.get(0).fileAssociations = java.util.List.of(proj);
            c.windows.binaries.get(1).fileAssociations = java.util.List.of(assoc(".vxt"));
        });
        JsonNode bins = manifest().path("versions").get(0).path("platforms").path("windows").path("binaries");
        JsonNode exe = bins.get(0).path("fileAssociations").get(0);
        assertEquals(".vxr", exe.path("extension").asText());
        assertEquals("myapp.vxr", exe.path("progId").asText());
        assertEquals("MyApp Project", exe.path("description").asText());
        assertEquals("application/x-vexelray", exe.path("contentType").asText());
        assertTrue(exe.path("gui").isMissingNode());
        JsonNode jar = bins.get(1).path("fileAssociations").get(0);
        assertEquals("MyApp VXT file", jar.path("description").asText());
        assertTrue(jar.path("gui").asBoolean());
        assertTrue(Files.readString(out.resolve("README.md")).contains("`.vxr`, `.vxt`"));
    }

    @Test
    void fileAssociationsAreValidated() throws IOException {
        java.util.function.Consumer<java.util.function.Consumer<InstallerConfig>> bad =
                tweak -> assertThrows(InstallerException.class, () -> runWith(tweak));
        bad.accept(c -> c.windows.binaries.get(0).fileAssociations = java.util.List.of(assoc("exe")));
        bad.accept(c -> c.windows.binaries.get(0).fileAssociations = java.util.List.of(assoc(".v x")));
        bad.accept(c -> c.windows.binaries.get(0).fileAssociations = java.util.List.of(assoc("")));
        bad.accept(c -> c.windows.binaries.get(0).fileAssociations = java.util.List.of(assoc("vxr"), assoc(".VXR")));
        bad.accept(c -> {
            c.windows.binaries.get(0).fileAssociations = java.util.List.of(assoc("vxr"));
            c.windows.binaries.get(1).fileAssociations = java.util.List.of(assoc("vxr"));
        });
        bad.accept(c -> {
            FileAssociation fa = assoc("vxr");
            fa.contentType = "not a mime";
            c.windows.binaries.get(0).fileAssociations = java.util.List.of(fa);
        });
        zip("rt.zip", "bin/java.exe");
        bad.accept(c -> {
            BinaryConfig rt = new BinaryConfig();
            rt.source = "src/rt.zip";
            rt.fileAssociations = java.util.List.of(assoc("vxr"));
            c.windows.binaries.add(rt);
        });
    }

    @Test
    void runtimeJvmArgsAndRequiresLandInTheManifest() throws IOException {
        runWith(c -> {
            BinaryConfig jar = c.windows.binaries.get(1);
            jar.runtime = "vexelray-runtime";
            jar.jvmArgs = java.util.List.of("--enable-native-access=ALL-UNNAMED", "-Xmx512m");
            Requirement rq = new Requirement();
            rq.id = "other-app";
            rq.min = "v2.1";
            c.windows.requires.add(rq);
        });
        JsonNode win = manifest().path("versions").get(0).path("platforms").path("windows");
        JsonNode jar = win.path("binaries").get(1);
        assertEquals("vexelray-runtime", jar.path("runtime").asText());
        assertEquals("-Xmx512m", jar.path("jvmArgs").get(1).asText());
        // a binary that gets its JVM from a runtime no longer demands a PATH java
        assertTrue(win.path("requires").path("java").isMissingNode());
        JsonNode installs = win.path("requires").path("installs");
        assertEquals("other-app", installs.get(0).path("id").asText());
        assertEquals("2.1", installs.get(0).path("min").asText());
        assertEquals("vexelray-runtime", installs.get(1).path("id").asText());
        assertTrue(installs.get(1).path("min").isMissingNode());
    }

    void zip(String name, String... entries) throws IOException {
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(src.resolve(name)))) {
            for (String e : entries) {
                z.putNextEntry(new java.util.zip.ZipEntry(e));
                z.write(e.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }

    @Test
    void anArchiveIsInferredFromZipAndListsItsExposedCommands() throws IOException {
        zip("rt.zip", "bin/java.exe", "bin/javaw.exe", "lib/modules");
        runWith(c -> {
            BinaryConfig rt = new BinaryConfig();
            rt.source = "src/rt.zip";
            rt.name = "runtime";
            rt.dir = "jre";
            rt.exposes = new java.util.LinkedHashMap<>();
            rt.exposes.put("java", "bin/java.exe");
            rt.exposes.put("javaw", "BIN/javaw.exe");
            c.windows.binaries.add(rt);
        });
        JsonNode e = manifest().path("versions").get(0).path("platforms").path("windows").path("binaries").get(2);
        assertEquals("archive", e.path("kind").asText());
        assertEquals("{installDir}\\jre", e.path("installPath").asText());
        assertFalse(e.path("executable").asBoolean());
        assertEquals("bin\\java.exe", e.path("exposes").path("java").asText());
        assertEquals("rt.zip", e.path("file").asText());
        // an archive has no launcher and demands no Java
        assertTrue(e.path("launcher").isMissingNode());
        assertTrue(Files.readString(out.resolve("README.md")).contains("archive (unpacked)"));
    }

    @Test
    void archivesAreValidated() throws IOException {
        zip("rt.zip", "bin/java.exe");
        java.util.function.Consumer<java.util.function.Consumer<BinaryConfig>> bad = tweak -> assertThrows(InstallerException.class,
                () -> runWith(c -> {
                    BinaryConfig rt = new BinaryConfig();
                    rt.source = "src/rt.zip";
                    rt.exposes = new java.util.LinkedHashMap<>(java.util.Map.of("java", "bin/java.exe"));
                    tweak.accept(rt);
                    c.windows.binaries.add(rt);
                }));
        bad.accept(rt -> rt.exposes.put("nope", "bin/missing.exe"));        // not in the zip
        bad.accept(rt -> rt.exposes.put("evil", "../outside.exe"));          // escapes the folder
        bad.accept(rt -> rt.exposes.put("abs", "/bin/java.exe"));
        bad.accept(rt -> rt.exposes.put("bad name", "bin/java.exe"));
        bad.accept(rt -> rt.addToPath = true);
        bad.accept(rt -> rt.runtime = "other");
        // exposes on a non-archive, and a .zip that is not a zip
        assertThrows(InstallerException.class, () -> runWith(c -> c.windows.binaries.get(0).exposes = java.util.Map.of("x", "y")));
        Files.writeString(src.resolve("broken.zip"), "not a zip");
        assertThrows(InstallerException.class, () -> runWith(c -> {
            BinaryConfig rt = new BinaryConfig();
            rt.source = "src/broken.zip";
            c.windows.binaries.add(rt);
        }));
    }

    @Test
    void aSignedNativeBinaryRecordsItsSignerInTheManifest() throws IOException {
        String thumb = "ab".repeat(20);
        runWith(c -> {
            c.windows.binaries.get(0).signedBy = "CN=Example Inc";
            c.windows.binaries.get(0).signerThumbprint = thumb;
        });
        JsonNode sg = manifest().path("versions").get(0).path("platforms").path("windows")
                .path("binaries").get(0).path("signature");
        assertEquals("CN=Example Inc", sg.path("subject").asText());
        assertEquals(thumb.toUpperCase(), sg.path("thumbprint").asText());
        // unsigned binaries carry no signature node
        assertTrue(manifest().path("versions").get(0).path("platforms").path("windows")
                .path("binaries").get(1).path("signature").isMissingNode());
    }

    @Test
    void signingOptionsAreValidated() {
        assertThrows(InstallerException.class, () -> runWith(c -> c.windows.binaries.get(1).signedBy = "CN=X")); // a jar
        assertThrows(InstallerException.class, () -> runWith(c -> c.windows.binaries.get(0).signerThumbprint = "ab".repeat(20)));
        assertThrows(InstallerException.class, () -> runWith(c -> {
            c.windows.binaries.get(0).signedBy = "CN=X";
            c.windows.binaries.get(0).signerThumbprint = "not-hex";
        }));
    }

    @Test
    void jvmArgsAndRuntimeAreValidated() {
        assertThrows(InstallerException.class, () -> runWith(c -> c.windows.binaries.get(0).runtime = "rt"));
        assertThrows(InstallerException.class, () -> runWith(c -> c.windows.binaries.get(1).jvmArgs = java.util.List.of("-Dx=\"y\"")));
        assertThrows(InstallerException.class, () -> runWith(c -> c.windows.binaries.get(1).jvmArgs = java.util.List.of("-Dx=%PATH%")));
        assertThrows(InstallerException.class, () -> runWith(c -> c.windows.binaries.get(1).runtime = "Bad Id"));
        assertThrows(InstallerException.class, () -> runWith(c -> {
            Requirement rq = new Requirement();
            c.windows.requires.add(rq);
        }));
    }

    @Test
    void nonAsciiNameIsRejectedWithAClearMessage() throws IOException {
        GenerationRequest r = new GenerationRequest();
        r.config = config();
        r.config.name = "Café";
        r.version = "1.0.0";
        r.outDir = out;
        r.assetsDir = assets;
        r.baseDir = tmp;
        // the name appears in the scripts, which must stay ASCII
        assertThrows(InstallerException.class, () -> new InstallerGenerator().generate(r));
    }
}
