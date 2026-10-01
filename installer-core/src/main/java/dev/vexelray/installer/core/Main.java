package dev.vexelray.installer.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line front end, for projects that don't use Maven (and for tests/smoke.ps1).
 *
 * <pre>
 * java -jar installer-core-*-cli.jar --config installer.json [--version 1.2.0] [--out dist]
 *      [--assets build/release-assets] [--notes "..."] [--base-url URL] [--asset-base-url URL]
 *      [--publish]        (token from $GITHUB_TOKEN)
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (InstallerException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args) {
        String config = "installer.json", version = null, out = "dist", assets = "build/release-assets";
        String notes = "", baseUrl = null, assetBaseUrl = null;
        boolean publish = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> config = value(args, ++i);
                case "--version" -> version = value(args, ++i);
                case "--out" -> out = value(args, ++i);
                case "--assets" -> assets = value(args, ++i);
                case "--notes" -> notes = value(args, ++i);
                case "--base-url" -> baseUrl = value(args, ++i);
                case "--asset-base-url" -> assetBaseUrl = value(args, ++i);
                case "--publish" -> publish = true;
                default -> throw new InstallerException("unknown argument " + args[i]);
            }
        }
        Path configPath = Path.of(config).toAbsolutePath();
        InstallerConfig cfg;
        try {
            cfg = Json.read(Files.readString(configPath, StandardCharsets.UTF_8), InstallerConfig.class);
        } catch (java.io.IOException e) {
            throw new InstallerException("cannot read config " + configPath + ": " + e.getMessage(), e);
        }
        GenerationRequest req = new GenerationRequest();
        req.config = cfg;
        req.version = version;
        req.outDir = Path.of(out).toAbsolutePath();
        req.assetsDir = Path.of(assets).toAbsolutePath();
        req.baseDir = configPath.getParent();
        req.notes = notes;
        req.baseUrl = baseUrl;
        req.assetBaseUrl = assetBaseUrl;

        GenerationResult res = new InstallerGenerator().generate(req);
        System.out.println("Generated " + cfg.name + " " + res.version() + " into " + req.outDir);
        System.out.println("  one-liner:  " + res.installCommand());
        if (publish) {
            String token = System.getenv("GITHUB_TOKEN");
            if (token == null || token.isBlank()) throw new InstallerException("--publish needs GITHUB_TOKEN");
            GithubReleasePublisher.github(token, s -> System.out.println("  " + s)).publish(res, cfg.name);
        } else {
            System.out.println("  next:       upload the files in " + req.assetsDir.resolve(res.version())
                    + " to the release tagged '" + res.tag() + "' on " + res.releasesRepo() + " (or re-run with --publish)");
        }
        System.out.println("              then commit the generated files in " + req.outDir + " to " + cfg.repo);
        return 0;
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) throw new InstallerException("missing value for " + args[i - 1]);
        return args[i];
    }
}
