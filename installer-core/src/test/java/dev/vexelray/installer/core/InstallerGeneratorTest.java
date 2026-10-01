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
