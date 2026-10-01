package dev.vexelray.installer.core;

import java.nio.file.Path;
import java.util.List;

/** What a run produced, for logging and for the publish step. */
public record GenerationResult(
        String version,
        String latest,
        String tag,
        /** owner/name the release belongs to; null when only a base-URL override was given. */
        String releasesRepo,
        String manifestUrl,
        String installCommand,
        /** Staged release artifacts, to upload to the release {@link #tag}. */
        List<Path> assets,
        /** Files written to the output directory. */
        List<Path> written,
        String notes,
        boolean prerelease) {}
