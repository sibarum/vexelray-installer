package dev.vexelray.installer.core;

import java.nio.file.Path;
import java.time.Clock;

/** Inputs of one generator run. */
public final class GenerationRequest {
    public InstallerConfig config;
    /** Overrides {@code config.version}. */
    public String version;
    /** Where install.ps1 &amp; co, manifest.json and the README are written (merged with what is there). */
    public Path outDir;
    /** Staging root for release artifacts: {@code <assetsDir>/<version>/<file>}. */
    public Path assetsDir;
    /** Relative artifact sources are resolved against this. */
    public Path baseDir = Path.of(".");
    public String notes = "";
    /** Overrides the public URL of {@link #outDir} (testing, or hosting somewhere other than raw.githubusercontent.com). */
    public String baseUrl;
    /** Overrides the release download base; URLs become {@code <base>/<tag>/<file>}. */
    public String assetBaseUrl;
    public Clock clock = Clock.systemUTC();
}
