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
 *
 * java -jar installer-core-*-cli.jar --suite suite.json [--out dist] [--base-url URL]
 * java -jar installer-core-*-cli.jar --suite suite.json --site [--out site]   (the landing page, at the suite's site.url)
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
        String suite = null;
        boolean publish = false, site = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> config = value(args, ++i);
                case "--suite" -> suite = value(args, ++i);
                case "--version" -> version = value(args, ++i);
                case "--out" -> out = value(args, ++i);
                case "--assets" -> assets = value(args, ++i);
                case "--notes" -> notes = value(args, ++i);
                case "--base-url" -> baseUrl = value(args, ++i);
                case "--asset-base-url" -> assetBaseUrl = value(args, ++i);
                case "--publish" -> publish = true;
                case "--site" -> site = true;
                default -> throw new InstallerException("unknown argument " + args[i]);
            }
        }
        if (site && suite == null) throw new InstallerException("--site needs --suite");
        if (site && baseUrl != null) throw new InstallerException("--site takes its address from the suite's site.url, not --base-url");
        if (suite != null) return suite(Path.of(suite).toAbsolutePath(), Path.of(out).toAbsolutePath(), baseUrl, site);
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

    private static int suite(Path suitePath, Path out, String baseUrl, boolean site) {
        SuiteConfig s;
        try {
            s = Json.read(Files.readString(suitePath, StandardCharsets.UTF_8), SuiteConfig.class);
        } catch (java.io.IOException e) {
            throw new InstallerException("cannot read suite " + suitePath + ": " + e.getMessage(), e);
        }
        if (site) {
            String command = new SuiteGenerator().generateSite(s, suitePath.getParent(), out);
            System.out.println("Generated the " + s.name + " landing page into " + out);
            System.out.println("  one-liner:  " + command);
            System.out.println("  next:       commit the generated files in " + out + " to the repo served at " + s.site.url);
            return 0;
        }
        String command = new SuiteGenerator().generate(s, suitePath.getParent(), out, baseUrl);
        System.out.println("Generated the " + s.name + " suite installer into " + out);
        System.out.println("  one-liner:  " + command);
        System.out.println("  next:       commit the generated files in " + out + " to " + s.repo);
        return 0;
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) throw new InstallerException("missing value for " + args[i - 1]);
        return args[i];
    }
}
