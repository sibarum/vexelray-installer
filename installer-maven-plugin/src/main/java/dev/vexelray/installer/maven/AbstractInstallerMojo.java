package dev.vexelray.installer.maven;

import dev.vexelray.installer.core.BinaryConfig;
import dev.vexelray.installer.core.GenerationRequest;
import dev.vexelray.installer.core.GenerationResult;
import dev.vexelray.installer.core.InstallerConfig;
import dev.vexelray.installer.core.InstallerException;
import dev.vexelray.installer.core.InstallerGenerator;
import dev.vexelray.installer.core.PlatformConfig;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared configuration: the same fields as installer.json, defaulted from the project. */
abstract class AbstractInstallerMojo extends AbstractMojo {
    private static final Pattern GITHUB = Pattern.compile("github\\.com[/:]([\\w.-]+)/([\\w.-]+?)(?:\\.git)?(?:/.*)?$");

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** Lowercase id of the application; names the install record and the Apps entry. */
    @Parameter(property = "installer.id", defaultValue = "${project.artifactId}")
    String id;

    @Parameter(property = "installer.name", defaultValue = "${project.name}")
    String name;

    @Parameter(property = "installer.publisher")
    String publisher;

    @Parameter(defaultValue = "${project.description}")
    String description;

    @Parameter(property = "installer.version", defaultValue = "${project.version}")
    String version;

    /** owner/name of the GitHub repo the generated files are committed to; defaults to the one in the pom's scm url. */
    @Parameter(property = "installer.repo")
    String repo;

    @Parameter(property = "installer.branch", defaultValue = "main")
    String branch;

    /** Sub-folder of the repo that holds the generated files. */
    @Parameter(property = "installer.repoPath")
    String repoPath;

    /** owner/name hosting the release downloads; defaults to {@code repo}. */
    @Parameter(property = "installer.releasesRepo")
    String releasesRepo;

    @Parameter(property = "installer.tagFormat", defaultValue = "v{version}")
    String tagFormat;

    /** The Windows installer: install folder and the binaries to ship. */
    @Parameter
    PlatformConfig windows;

    /** Where install.ps1, manifest.json and the README are written; commit these to the project repo. */
    @Parameter(property = "installer.outputDirectory", defaultValue = "${project.build.directory}/installer")
    File outputDirectory;

    /** Where the artifacts are staged, ready to upload to the release. */
    @Parameter(property = "installer.assetsDirectory", defaultValue = "${project.build.directory}/installer-assets")
    File assetsDirectory;

    @Parameter(property = "installer.notes")
    String notes;

    /** Overrides the public URL of the output directory. */
    @Parameter(property = "installer.baseUrl")
    String baseUrl;

    /** Overrides the release download base ({@code <base>/<tag>/<file>}). */
    @Parameter(property = "installer.assetBaseUrl")
    String assetBaseUrl;

    @Parameter(property = "installer.skip", defaultValue = "false")
    boolean skip;

    GenerationResult generate() throws MojoExecutionException {
        InstallerConfig c = new InstallerConfig();
        c.id = id;
        c.name = name != null ? name : project.getArtifactId();
        c.publisher = publisher;
        c.description = description == null ? null : description.trim().replaceAll("\\s+", " ");
        c.version = version;
        c.repo = repo != null ? repo : repoFromScm();
        c.branch = branch;
        c.repoPath = repoPath;
        c.releasesRepo = releasesRepo;
        c.tagFormat = tagFormat;
        c.windows = windows;
        if (windows != null && windows.binaries != null) {
            for (BinaryConfig b : windows.binaries) resolveAttached(b);
        }

        GenerationRequest req = new GenerationRequest();
        req.config = c;
        req.outDir = outputDirectory.toPath();
        req.assetsDir = assetsDirectory.toPath();
        req.baseDir = project.getBasedir().toPath();
        req.notes = notes == null ? "" : notes;
        req.baseUrl = baseUrl;
        req.assetBaseUrl = assetBaseUrl;
        try {
            GenerationResult res = new InstallerGenerator().generate(req);
            getLog().info("Installer for " + c.name + " " + res.version() + " written to " + outputDirectory);
            getLog().info("  install command: " + res.installCommand());
            return res;
        } catch (InstallerException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /** {@code <attached>main</attached>} = the project's own artifact; anything else = an attached classifier. */
    private void resolveAttached(BinaryConfig b) throws MojoExecutionException {
        if (b.attached == null || b.attached.isBlank()) return;
        if (b.source != null && !b.source.isBlank())
            throw new MojoExecutionException("a binary sets both <source> and <attached>; use one");
        File file = null;
        if (b.attached.equals("main")) {
            Artifact main = project.getArtifact();
            file = main == null ? null : main.getFile();
        } else {
            for (Artifact a : project.getAttachedArtifacts()) {
                if (b.attached.equals(a.getClassifier())) file = a.getFile();
            }
        }
        if (file == null || !file.isFile())
            throw new MojoExecutionException("no built artifact for <attached>" + b.attached
                    + "</attached>; run this goal after the phase that produces it (package)");
        b.source = Path.of(file.getAbsolutePath()).toString();
    }

    private String repoFromScm() {
        if (project.getScm() == null) return null;
        for (String url : new String[] {project.getScm().getUrl(), project.getScm().getConnection(), project.getScm().getDeveloperConnection()}) {
            if (url == null) continue;
            Matcher m = GITHUB.matcher(url);
            if (m.find()) return m.group(1) + "/" + m.group(2);
        }
        return null;
    }
}
