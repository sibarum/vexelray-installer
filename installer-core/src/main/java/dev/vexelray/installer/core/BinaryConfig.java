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
    /**
     * {@code native}, {@code jar}, {@code archive} (a .zip unpacked into {@code dir}) or {@code file} (placed as it is,
     * never run: a licence or a readme); inferred from the extension, {@code .txt}, {@code .md}, {@code .html},
     * {@code .htm}, {@code .pdf} and {@code .rtf} being files.
     */
    public String kind;
    /** Sub-folder of the install dir. */
    public String dir;
    /** Per-binary version, when it differs from the release version. */
    public String version;
    public Boolean addToPath;
    public ShortcutConfig shortcuts;
    /**
     * Linux: the name this program's windows go by on the desktop ({@code WM_CLASS}, or the Wayland {@code app_id}),
     * such as {@code dev.example.myapp}. Its desktop entry is named after it and says it as {@code StartupWMClass}, so
     * a dock shows the running program under the entry's icon. Without it the entry gets a name of the installer's
     * own, which no window matches. A desktop entry name, so dot-separated parts of letters, digits, {@code _} and
     * {@code -}, none starting with a digit. Not used on Windows yet.
     */
    public String appId;
    /**
     * Icon for the shortcuts and the Apps entry: an {@code .ico}, or a square {@code .png} (256px or
     * more) that is converted. Shipped next to the binary. Native exes fall back to their own icon.
     */
    public String icon;
    /**
     * Extensions this binary opens ({@code "%1"} is passed as the last argument). A type without an
     * {@code icon} of its own uses this binary's {@code icon}, or a native exe's own icon. Not for archives.
     */
    public java.util.List<FileAssociation> fileAssociations;
    /** Minimum Java major version, for jars. */
    public Integer javaMin;
    /** JVM options placed before {@code -jar} in the launcher and the shortcut. Jars only. */
    public java.util.List<String> jvmArgs;
    /**
     * Id of another install that provides the JVM ({@code <its dir>\bin\java.exe}); the launcher finds it
     * through the registry at launch time, so a runtime update needs no regeneration. Jars only.
     */
    public String runtime;
    /**
     * Archives only: command name -> path inside the archive (for example {@code java -> bin/java.exe}).
     * Recorded as install-record commands so other programs can run them.
     */
    public java.util.Map<String, String> exposes;
    /**
     * Native executables only: the subject (for example {@code CN=Example Inc}) the file is Authenticode-signed
     * with. The installer refuses a file whose signature is missing or from someone else. Sign before
     * generating: the manifest hashes the file as given.
     */
    public String signedBy;
    /**
     * Optional SHA-1 thumbprint of the signing certificate. With it the installer accepts a certificate Windows
     * does not chain to a trusted root (a self-signed one) as long as it is exactly this one; without it the
     * signature must be trusted.
     */
    public String signerThumbprint;
}
