package dev.vexelray.installer.core;

/** A problem with the config, the artifacts or a template; the message is meant for the user. */
public final class InstallerException extends RuntimeException {
    public InstallerException(String message) { super(message); }
    public InstallerException(String message, Throwable cause) { super(message, cause); }
}
