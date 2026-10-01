package dev.vexelray.installer.maven;

import dev.vexelray.installer.core.GenerationResult;
import dev.vexelray.installer.core.GithubReleasePublisher;
import dev.vexelray.installer.core.InstallerException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Runs {@code generate}, then creates the GitHub release for the version's tag and uploads the
 * artifacts to it. Not bound to a phase: publishing is always an explicit
 * {@code mvn package installer:publish}. The token comes from an environment variable and needs
 * write access to the releases repository's contents.
 */
@Mojo(name = "publish", requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
public class PublishMojo extends AbstractInstallerMojo {
    /** Environment variable holding the GitHub token. */
    @Parameter(property = "installer.tokenEnv", defaultValue = "GITHUB_TOKEN")
    String tokenEnv;

    @Parameter(property = "installer.githubApi", defaultValue = "https://api.github.com")
    String githubApi;

    /** Generate and report what would be uploaded, but do not touch GitHub. */
    @Parameter(property = "installer.dryRun", defaultValue = "false")
    boolean dryRun;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Installer publishing skipped");
            return;
        }
        GenerationResult res = generate();
        if (dryRun) {
            getLog().info("Dry run: would upload " + res.assets().size() + " file(s) to release " + res.tag() + " of " + res.releasesRepo());
            return;
        }
        String token = System.getenv(tokenEnv);
        if (token == null || token.isBlank())
            throw new MojoExecutionException("environment variable " + tokenEnv + " is not set (needs a GitHub token with write access to " + res.releasesRepo() + ")");
        try {
            new GithubReleasePublisher(githubApi, token, s -> getLog().info(s)).publish(res, name);
        } catch (InstallerException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        getLog().info("Published. Now commit the files in " + outputDirectory + " to " + (repo != null ? repo : "the project repo") + ".");
    }
}
