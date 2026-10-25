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
        assertTrue(readme.contains("& ([scriptblock]::Create((irm https://raw.githubusercontent.com/owner/framework/main/docs/install/install.ps1)))"
                + " --install-everything --do-not-prompt-me"));
    }

    @Test
    void readmeCommandsUsePowershellVariables() throws IOException {
        generate(suite());

        String readme = Files.readString(out.resolve("README.md"));
        PowershellFences.assertNoCmdVariables(readme);
        assertTrue(readme.contains("& \"$env:LOCALAPPDATA\\Programs\\Editor\\update.cmd\""));
        assertTrue(readme.contains("& \"$env:LOCALAPPDATA\\Programs\\Editor\\uninstall.cmd\" -Id editor"));
        assertTrue(readme.contains("`%LOCALAPPDATA%\\vexelray-installer\\installs`"), "prose keeps the Windows form");
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
    void aPinnedVersionGoesIntoTheScriptAndTheReadme() throws IOException {
        SuiteConfig s = suite();
        s.members.get(0).version = "v0.2.0-SNAPSHOT";
        generate(s);

        String script = Files.readString(out.resolve("install.ps1"), StandardCharsets.UTF_8);
        assertTrue(script.contains("\"version\":\"0.2.0-SNAPSHOT\""), "the pin, without its v");
        assertEquals(1, script.split("\"version\":", -1).length - 1, "only the pinned member has one");
        String readme = Files.readString(out.resolve("README.md"), StandardCharsets.UTF_8);
        assertTrue(readme.contains("| Application | What it is | Id | Version | Installed to |"));
        assertTrue(readme.contains("| `editor` | `0.2.0-SNAPSHOT` |"));
        assertTrue(readme.contains("| `explorer` | latest |"));
        assertTrue(readme.contains("installs these versions again"));
        assertFalse(readme.contains("{{"));
    }

    @Test
    void withoutPinsTheReadmeHasNoVersionColumn() throws IOException {
        generate(suite());
        String readme = Files.readString(out.resolve("README.md"), StandardCharsets.UTF_8);
        assertTrue(readme.contains("| Application | What it is | Id | Installed to |"));
        assertTrue(readme.contains("updates each of them to its latest version"));
    }

    @Test
    void anOptionalMemberIsMarkedInTheScriptAndNamedInTheReadme() throws IOException {
        SuiteConfig s = suite();
        s.members.get(1).optional = true;
        generate(s);

        String script = Files.readString(out.resolve("install.ps1"), StandardCharsets.UTF_8);
        assertEquals(1, script.split("\"optional\":true", -1).length - 1, "only the optional member is marked");
        assertTrue(script.indexOf("\"optional\":true") > script.indexOf("\"id\":\"explorer\""));
        String readme = Files.readString(out.resolve("README.md"), StandardCharsets.UTF_8);
        assertTrue(readme.contains("```\n\nExplorer starts unticked, and without the window it is left out unless already "
                + "installed. Tick it to install it too, or name `explorer` in `INSTALL_ONLY`.\n\n### Everything"), readme);
    }

    @Test
    void withNoOptionalMemberTheReadmeIsAsBefore() throws IOException {
        generate(suite());
        assertFalse(Files.readString(out.resolve("install.ps1")).contains("\"optional\""));
        assertTrue(Files.readString(out.resolve("README.md")).contains("```\n\n### Everything"));
    }

    @Test
    void aPinnedMemberIsNeverOptional() throws IOException {
        SuiteConfig s = suite();
        s.members.get(1).optional = true;
        s.members.get(1).version = "0.2.0-SNAPSHOT";
        generate(s);
        assertFalse(Files.readString(out.resolve("install.ps1")).contains("\"optional\""), "a preflight installs everything");
        assertFalse(Files.readString(out.resolve("README.md")).contains("unticked"));
    }

    @Test
    void refusesAPinThatIsNotAVersion() {
        SuiteConfig s = suite();
        s.members.get(1).version = "1.0'; rm";
        InstallerException e = assertThrows(InstallerException.class, () -> generate(s));
        assertTrue(e.getMessage().contains("explorer"), e.getMessage());
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

    /** The explorer member, with a Linux build as well; the editor stays Windows only. */
    void explorerOnLinux() throws IOException {
        Files.writeString(tmp.resolve("explorer.json"), """
                {"id": "explorer", "name": "Explorer", "repo": "owner/explorer", "branch": "dev", "repoPath": "dist",
                 "windows": {"binaries": [{"source": "x.exe"}]},
                 "linux": {"binaries": [{"source": "x", "addToPath": true}]}}
                """);
    }

    @Test
    void linuxMembersGetAnInstallShOfTheirOwn() throws IOException {
        explorerOnLinux();
        String command = generate(suite());

        assertEquals("irm https://raw.githubusercontent.com/owner/framework/main/docs/install/install.ps1 | iex", command);
        String sh = Files.readString(out.resolve("install.sh"), StandardCharsets.UTF_8);
        assertTrue(sh.startsWith("#!/bin/sh\n") && sh.chars().allMatch(ch -> ch < 0x80) && !sh.contains("\r"));
        assertTrue(sh.contains("vi_member 'explorer' 'Explorer' 'https://raw.githubusercontent.com/owner/explorer/dev/dist/manifest.linux.txt' '-' 0 1"));
        assertTrue(sh.contains("vi_member 'editor' 'Editor' "), "listed, so the script can say it has no Linux version");
        assertTrue(sh.contains("' '-' 0 0 '-'"));
        assertFalse(sh.matches("(?s).*\\{\\{[A-Z_]+}}.*"));

        String readme = Files.readString(out.resolve("README.md"), StandardCharsets.UTF_8);
        assertTrue(readme.indexOf("## Windows") < readme.indexOf("## Linux"));
        assertTrue(readme.contains("curl -fsSL https://raw.githubusercontent.com/owner/framework/main/docs/install/install.sh | sh"));
        assertTrue(readme.contains("Editor has no Linux version yet"));
        assertTrue(readme.contains("`~/.local/opt/explorer`"));
        assertTrue(readme.contains("A macOS installer is planned."));
    }

    @Test
    void withoutLinuxMembersThereIsNoInstallSh() throws IOException {
        generate(suite());
        assertFalse(Files.exists(out.resolve("install.sh")));
        assertFalse(Files.readString(out.resolve("README.md"), StandardCharsets.UTF_8).contains("## Linux"));
    }
}
