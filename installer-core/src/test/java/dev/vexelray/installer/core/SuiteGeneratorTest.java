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

    // --- The landing page --------------------------------------------------------------------------------

    SuiteConfig siteSuite() throws IOException {
        SuiteConfig s = suite();
        s.site = new SuiteConfig.Site();
        s.site.url = "https://owner.github.io/";
        s.site.tagline = "Two apps & one <line>.";
        s.site.badge = "0.1.0 prerelease";
        s.site.requirements = "Needs `Vulkan`.";
        s.site.feedbackUrl = "https://github.com/owner/framework/issues/new";
        SuiteConfig.Link docs = new SuiteConfig.Link();
        docs.label = "Docs";
        docs.url = "https://owner.github.io/docs/";
        s.site.links.add(docs);
        return s;
    }

    String site(SuiteConfig s) { return new SuiteGenerator().generateSite(s, tmp, out); }

    @Test
    void theSiteCarriesItsOwnCopyOfTheScriptUnderItsOwnAddress() throws IOException {
        String command = site(siteSuite());

        assertEquals("irm https://owner.github.io/install.ps1 | iex", command);
        String script = Files.readString(out.resolve("install.ps1"), StandardCharsets.UTF_8);
        assertTrue(script.contains("irm https://owner.github.io/install.ps1 | iex"));
        // the manifests stay where the members' own runs published them
        assertTrue(script.contains("https://raw.githubusercontent.com/owner/editor/main/manifest.json"));
        assertTrue(Files.exists(out.resolve(".nojekyll")));
        assertFalse(Files.exists(out.resolve("README.md")), "the site has a page, not a README");
    }

    @Test
    void theReadmePointsToTheSite() throws IOException {
        generate(siteSuite());
        assertTrue(Files.readString(out.resolve("README.md")).contains("The install page is https://owner.github.io,"));
        generate(suite());
        assertFalse(Files.readString(out.resolve("README.md")).contains("The install page is"));
    }

    @Test
    void thePageShowsTheCommandAndEveryMember() throws IOException {
        site(siteSuite());

        String page = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
        assertFalse(page.contains("{{"), "every token filled");
        assertTrue(page.contains("<code id=\"install-command\">irm https://owner.github.io/install.ps1 | iex</code>"));
        assertTrue(page.contains("Two apps &amp; one &lt;line&gt;."), "config text is escaped");
        assertTrue(page.contains("Needs <code>Vulkan</code>."), "backticks become code");
        assertTrue(page.contains("<a href=\"../apps/editor.html\">Editor</a>"));
        assertTrue(page.contains("<h3>Explorer</h3>"));
        assertTrue(page.indexOf("id=\"app-editor\"") < page.indexOf("id=\"app-explorer\""), "members in listed order");
        assertTrue(page.contains("$env:INSTALL_ONLY = 'editor,explorer'"));
        assertTrue(page.contains("&amp; \"$env:LOCALAPPDATA\\Programs\\Editor\\uninstall.cmd\" -All"));
        assertTrue(page.contains("<a href=\"https://owner.github.io/docs/\">Docs</a>"));
        assertTrue(page.contains("https://github.com/owner/framework/issues/new"));
        assertTrue(page.contains("<span class=\"badge\">0.1.0 prerelease</span>"));
    }

    @Test
    void thePageSaysWhatTheMembersChange() throws IOException {
        Files.writeString(tmp.resolve("editor.json"), """
                {"id": "editor", "name": "Editor", "repo": "owner/editor",
                 "windows": {"binaries": [{"source": "target/editor.exe", "addToPath": true,
                   "shortcuts": {"desktop": true, "startMenu": true},
                   "fileAssociations": [{"extension": ".txt"}]}]}}
                """);
        site(siteSuite());

        String page = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(page.contains("<li>each application in its own folder</li>"), "the two install under different parents");
        assertTrue(page.contains("<li>desktop and Start menu shortcuts</li>"));
        assertTrue(page.contains("so <code>editor</code> works from a terminal"));
        assertTrue(page.contains("the file types Editor reads"));
    }

    @Test
    void iconsAndScreenshotsAreCopiedBesideThePage() throws IOException {
        // an .ico whose only frame is a PNG: the page serves the PNG
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
        java.nio.ByteBuffer ico = java.nio.ByteBuffer.allocate(22 + png.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) 1);
        ico.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
        ico.putInt(png.length).putInt(22).put(png);
        Files.write(tmp.resolve("editor.ico"), ico.array());
        Files.writeString(tmp.resolve("editor.json"), """
                {"id": "editor", "name": "Editor", "repo": "owner/editor",
                 "windows": {"binaries": [{"source": "target/editor.exe", "icon": "editor.ico"}]}}
                """);
        Files.write(tmp.resolve("shot.png"), png);
        SuiteConfig s = siteSuite();
        s.members.get(0).image = "shot.png";
        s.members.get(0).imageAlt = "Editor, \"editing\"";
        site(s);

        assertArrayEquals(png, Files.readAllBytes(out.resolve("img/editor.png")));
        assertArrayEquals(png, Files.readAllBytes(out.resolve("img/editor-screen.png")));
        String page = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(page.contains("<link rel=\"icon\" href=\"img/editor.png\">"));
        assertTrue(page.contains("src=\"img/editor-screen.png\" alt=\"Editor, &quot;editing&quot;\""));
    }

    @Test
    void theSiteNeedsAnHttpsAddress() throws IOException {
        SuiteConfig none = suite();
        assertThrows(InstallerException.class, () -> site(none));
        SuiteConfig http = siteSuite();
        http.site.url = "http://owner.github.io";
        assertThrows(InstallerException.class, () -> site(http));
    }

    SuiteConfig.Section buildSection() {
        SuiteConfig.Section sec = new SuiteConfig.Section();
        sec.id = "build";
        sec.title = "Build your own";
        sec.nav = "Developers";
        sec.lede = "Start from `the template`.";
        SuiteConfig.Card linked = new SuiteConfig.Card();
        linked.title = "Generate";
        linked.text = "One <command>.";
        linked.url = "https://owner.github.io/docs/start.html";
        SuiteConfig.Card plain = new SuiteConfig.Card();
        plain.title = "Edit";
        plain.text = "In `Editor`.";
        sec.cards.add(linked);
        sec.cards.add(plain);
        sec.action = new SuiteConfig.Link();
        sec.action.label = "Read the guide";
        sec.action.url = "https://owner.github.io/docs/";
        return sec;
    }

    @Test
    void aSectionOfTheSuitesOwnSitsBetweenTheMembersAndTheDetails() throws IOException {
        SuiteConfig s = siteSuite();
        s.site.sections.add(buildSection());
        site(s);

        String page = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
        assertFalse(page.contains("{{"), "every token filled");
        int apps = page.indexOf("<section class=\"band\" id=\"apps\""), build = page.indexOf("<section class=\"band\" id=\"build\""), details = page.indexOf("<section class=\"band\" id=\"details\"");
        assertTrue(apps >= 0, "the members' section is there");
        assertTrue(apps < build && build < details, "in order");
        assertTrue(page.contains("<h2 id=\"build-title\">Build your own</h2>"));
        assertTrue(page.contains("<p class=\"lede\">Start from <code>the template</code>.</p>"));
        assertTrue(page.contains("<h3><a href=\"https://owner.github.io/docs/start.html\">Generate</a></h3><p>One &lt;command&gt;.</p>"));
        assertTrue(page.contains("<h3>Edit</h3><p>In <code>Editor</code>.</p>"));
        assertTrue(page.contains("<a class=\"button\" href=\"https://owner.github.io/docs/\">Read the guide</a>"));
        assertTrue(page.contains("<a href=\"#apps\">Applications</a><a href=\"#build\">Developers</a><a href=\"#details\">Details</a><a href=\"https://owner.github.io/docs/\">Docs</a>"),
                "the header names it, after the members");
    }

    @Test
    void withNoSectionsThePageIsAsBefore() throws IOException {
        site(siteSuite());
        String page = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
        assertFalse(page.contains("class=\"card\""));
        assertTrue(page.contains("<a href=\"#apps\">Applications</a><a href=\"#details\">Details</a>"));
    }

    @Test
    void refusesASectionItCannotLinkTo() throws IOException {
        SuiteConfig taken = siteSuite();
        SuiteConfig.Section apps = buildSection();
        apps.id = "apps";
        taken.site.sections.add(apps);
        assertThrows(InstallerException.class, () -> site(taken));
        assertFalse(Files.exists(out.resolve("install.ps1")), "nothing written");

        SuiteConfig badId = siteSuite();
        SuiteConfig.Section spaced = buildSection();
        spaced.id = "Build It";
        badId.site.sections.add(spaced);
        assertThrows(InstallerException.class, () -> site(badId));

        SuiteConfig twice = siteSuite();
        twice.site.sections.add(buildSection());
        twice.site.sections.add(buildSection());
        assertThrows(InstallerException.class, () -> site(twice));

        SuiteConfig emptyCard = siteSuite();
        SuiteConfig.Section sec = buildSection();
        sec.cards.get(1).text = " ";
        emptyCard.site.sections.add(sec);
        InstallerException e = assertThrows(InstallerException.class, () -> site(emptyCard));
        assertTrue(e.getMessage().contains("build"), e.getMessage());
    }

    @Test
    void aLogoAHeroButtonAndACardsStat() throws IOException {
        Files.writeString(tmp.resolve("mark.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        SuiteConfig s = siteSuite();
        s.site.logo = "mark.svg";
        s.site.heroAction = new SuiteConfig.Link();
        s.site.heroAction.label = "Inside & out";
        s.site.heroAction.url = "https://owner.github.io/tech.html";
        SuiteConfig.Section sec = buildSection();
        sec.cards.get(0).stat = "0 fps";
        s.site.sections.add(sec);
        site(s);

        String page = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(Files.isRegularFile(out.resolve("img/logo.svg")), "the logo is copied beside the page");
        assertTrue(page.contains("<link rel=\"icon\" href=\"img/logo.svg\">"), "and is the tab icon");
        assertTrue(page.contains("<a class=\"brand\" href=\"./\"><img src=\"img/logo.svg\" alt=\"\">"), "and sits beside the name");
        assertTrue(page.contains("<a class=\"button ghost\" href=\"https://owner.github.io/tech.html\">Inside &amp; out</a>"));
        assertTrue(page.contains("<span class=\"stat\">0 fps</span><h3>"));

        SuiteConfig noFile = siteSuite();
        noFile.site.logo = "missing.svg";
        assertThrows(InstallerException.class, () -> site(noFile));
        SuiteConfig halfAction = siteSuite();
        halfAction.site.heroAction = new SuiteConfig.Link();
        halfAction.site.heroAction.label = "Read";
        assertThrows(InstallerException.class, () -> site(halfAction));
    }

    @Test
    void footerLinksAndANotice() throws IOException {
        SuiteConfig s = siteSuite();
        SuiteConfig.Link licenses = new SuiteConfig.Link();
        licenses.label = "Licenses";
        licenses.url = "https://owner.github.io/licenses.html";
        s.site.footerLinks.add(licenses);
        s.site.notice = "Thing is a trademark of `Owner` & co.";
        site(s);

        String page = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(page.contains(" &middot; <a href=\"https://owner.github.io/licenses.html\">Licenses</a></p>"));
        assertTrue(page.contains("<p class=\"legal\">Thing is a trademark of <code>Owner</code> &amp; co.</p>"));

        SuiteConfig half = siteSuite();
        half.site.footerLinks.add(new SuiteConfig.Link());
        out = tmp.resolve("site/other");
        assertThrows(InstallerException.class, () -> site(half));
        assertFalse(Files.exists(out.resolve("install.ps1")), "a bad footer link is caught before anything is written");
    }

    @Test
    void refusesAMissingScreenshot() throws IOException {
        SuiteConfig s = siteSuite();
        s.members.get(1).image = "nope.png";
        InstallerException e = assertThrows(InstallerException.class, () -> site(s));
        assertTrue(e.getMessage().contains("explorer"), e.getMessage());
    }
}
