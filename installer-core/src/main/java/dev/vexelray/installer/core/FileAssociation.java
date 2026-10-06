package dev.vexelray.installer.core;

/**
 * A file extension the binary opens. Registered per user: the binary is offered under "Open with",
 * and becomes the default only for an extension nothing else has claimed.
 */
public final class FileAssociation {
    /** The extension, with or without the leading dot ({@code .vxr} or {@code vxr}). */
    public String extension;
    /** The file type's name in Explorer (for example {@code MyApp Project}); defaults to "{name} file". */
    public String description;
    /** Optional MIME type, recorded only when the extension has none yet. */
    public String contentType;
}
