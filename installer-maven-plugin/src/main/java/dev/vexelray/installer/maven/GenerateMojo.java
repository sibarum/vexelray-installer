package dev.vexelray.installer.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Generates the installer, uninstaller and updater scripts, the merged install manifest and a
 * README with the one-line install command, and stages the artifacts for upload. Nothing leaves
 * the machine; see {@code installer:publish} for the GitHub release.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
public class GenerateMojo extends AbstractInstallerMojo {
    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Installer generation skipped");
            return;
        }
        generate();
    }
}
