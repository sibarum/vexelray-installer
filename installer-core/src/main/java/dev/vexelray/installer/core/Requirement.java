package dev.vexelray.installer.core;

/** Another install (by id) that must already be present, at {@code min} or newer when given. */
public final class Requirement {
    public String id;
    public String min;
}
