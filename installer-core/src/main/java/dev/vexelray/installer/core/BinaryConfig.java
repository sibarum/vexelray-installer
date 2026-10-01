package dev.vexelray.installer.core;

/** One file to install. Exactly one of {@code source} (a path) or, in Maven, {@code attached}. */
public final class BinaryConfig {
    /** Short name; names the jar launcher and the shortcut label. Defaults to the file name stem. */
    public String name;
    /** Path of the built artifact (relative to the config's base directory). */
    public String source;
    /**
     * Maven only: take the artifact from the project instead of {@code source}. {@code main} is the
     * primary artifact (for example the shaded jar); anything else is an attached classifier.
     */
    public String attached;
    /** File name as installed and as uploaded to the release; defaults to the source's name. */
    public String file;
    /** {@code native} or {@code jar}; inferred from the extension when absent. */
    public String kind;
    /** Sub-folder of the install dir. */
    public String dir;
    /** Per-binary version, when it differs from the release version. */
    public String version;
    public Boolean addToPath;
    public ShortcutConfig shortcuts;
    /** Minimum Java major version, for jars. */
    public Integer javaMin;
}
