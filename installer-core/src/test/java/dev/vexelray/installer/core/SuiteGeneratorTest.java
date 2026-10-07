package dev.vexelray.installer.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SuiteGeneratorTest {
    @TempDir Path tmp;
    Path out;

    @BeforeEach
    void setUp() throws IOException {
        out = tmp.resolve("site/install");
        Files.writeString(tmp.resolve("editor.json"), """
                {"id": "editor", "name": "Editor", "description": "Edits text.", "repo": "owner/editor",
                 "windows": {"binaries": [{"source": "target/editor.exe"}]}}
                """);
        Files.writeString(tmp.resolve("explorer.json"), """
                {"id": "explorer", "name": "Explorer", "repo": "owner/explorer", "branch": "dev", "repoPath": "dist",
                 "windows": {"installDir": "%LOCALAPPDATA%\\\\Apps\\\\{name}", "binaries": [{"source": "x.exe"}]}}
                """);
    }

    SuiteConfig suite() {
        SuiteConfig s = new SuiteConfig();
        s.id = "suite";
        s.name = "The Suite";
        s.description = "Two apps.";
        s.repo = "owner/framework";
        s.repoPath = "docs/install";
        SuiteConfig.Member a = new SuiteConfig.Member();
        a.config = "editor.json";
        a.page = "../apps/editor.html";
        SuiteConfig.Member b = new SuiteConfig.Member();
        b.config = "explorer.json";
        s.members.add(a);
        s.members.add(b);
        return s;
    }

    String generate(SuiteConfig s) { return new SuiteGenerator().generate(s, tmp, out, null); }

    @Test
    void writesAnAsciiScriptThatNamesEachMembersManifest() throws IOException {
        String command = generate(suite());

        assertEquals("irm https://raw.githubusercontent.com/owner/framework/main/docs/install/install.ps1 | iex", command);
        String script = Files.readString(out.resolve("install.ps1"), StandardCharsets.UTF_8);
        assertTrue(script.chars().allMatch(ch -> ch < 0x80), "ASCII only");
        assertFalse(script.contains("\r"), "LF only");
        // the URL each member's own run bakes into its scripts, derived the same way
        assertTrue(script.contains("https://raw.githubusercontent.com/owner/editor/main/manifest.json"));
        assertTrue(script.contains("https://raw.githubusercontent.com/owner/explorer/dev/dist/manifest.json"));
        assertTrue(script.indexOf("\"id\":\"editor\"") < script.indexOf("\"id\":\"explorer\""), "members in listed order");
    }

    @Test
    void readmeCarriesTheCommandAndATableOfMembers() throws IOException {
        generate(suite());

        String readme = Files.readString(out.resolve("README.md"));
        assertTrue(readme.contains(InstallerGenerator.README_MARKER));
        assertTrue(readme.contains("irm https://raw.githubusercontent.com/owner/framework/main/docs/install/install.ps1 | iex"));
        assertTrue(readme.contains("| [Editor](../apps/editor.html) | Edits text. | `editor` | `%LOCALAPPDATA%\\Programs\\Editor` |"));
        assertTrue(readme.contains("| Explorer | Explorer. | `explorer` | `%LOCALAPPDATA%\\Apps\\Explorer` |"));
        assertTrue(readme.contains("$env:INSTALL_ONLY = 'editor,explorer'"));
    }

    @Test
    void aHandWrittenReadmeIsLeftAlone() throws IOException {
        Files.createDirectories(out);
        Files.writeString(out.resolve("README.md"), "mine");
        generate(suite());
        assertEquals("mine", Files.readString(out.resolve("README.md")));
        assertTrue(Files.exists(out.resolve("INSTALL.md")));
    }

    @Test
    void aManifestUrlOverrideWins() throws IOException {
        SuiteConfig s = suite();
        s.members.get(0).manifestUrl = "http://127.0.0.1:1234/editor/manifest.json";
        generate(s);
        assertTrue(Files.readString(out.resolve("install.ps1")).contains("http://127.0.0.1:1234/editor/manifest.json"));
    }

    @Test
    void refusesAMemberListedTwice() {
        SuiteConfig s = suite();
        s.members.get(1).config = "editor.json";
        InstallerException e = assertThrows(InstallerException.class, () -> generate(s));
        assertTrue(e.getMessage().contains("twice"), e.getMessage());
    }

    @Test
    void refusesAMissingMemberConfig() {
        SuiteConfig s = suite();
        s.members.get(1).config = "nope.json";
        assertThrows(InstallerException.class, () -> generate(s));
    }

    @Test
    void refusesAMemberWithNoWindowsSection() throws IOException {
        Files.writeString(tmp.resolve("explorer.json"), "{\"id\": \"explorer\", \"name\": \"Explorer\", \"repo\": \"o/e\"}");
        InstallerException e = assertThrows(InstallerException.class, () -> generate(suite()));
        assertTrue(e.getMessage().contains("windows"), e.getMessage());
    }
}
