package dev.vexelray.installer.core;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Version ordering shared with the generated scripts: numeric core compared part by part, and a
 * pre-release ({@code -rc1}, {@code -SNAPSHOT}) sorts before the release with the same core.
 */
public final class Versions {
    private Versions() {}

    public record Key(int[] core, boolean pre) {}

    public static String strip(String v) { return v.replaceFirst("^[vV]", ""); }

    public static Key parse(String version) {
        String core = strip(version).replaceFirst("[-+].*$", "");
        String[] parts = core.split("\\.");
        if (parts.length > 4) throw bad(version);
        int[] nums = new int[4];
        try {
            for (int i = 0; i < parts.length; i++) nums[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            throw bad(version);
        }
        return new Key(nums, version.contains("-"));
    }

    private static InstallerException bad(String v) {
        return new InstallerException("'" + v + "' is not a version number (use e.g. 1.2.3 or 1.2.3-rc1)");
    }

    public static int compare(String a, String b) {
        Key x = parse(a), y = parse(b);
        int c = Arrays.compare(x.core(), y.core());
        if (c != 0) return c;
        if (x.pre() && !y.pre()) return -1;
        if (!x.pre() && y.pre()) return 1;
        return 0;
    }

    /** Newest first; for equal numeric cores a release comes before its pre-releases. */
    public static final Comparator<String> NEWEST_FIRST = (a, b) -> {
        Key x = parse(a), y = parse(b);
        int c = Arrays.compare(y.core(), x.core());
        if (c != 0) return c;
        return Boolean.compare(x.pre(), y.pre());
    };

    public static boolean isPre(String v) { return parse(v).pre(); }
}
