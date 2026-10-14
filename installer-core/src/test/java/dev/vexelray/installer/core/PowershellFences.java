package dev.vexelray.installer.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code ```powershell} code blocks of a generated page, which readers paste into PowerShell as they are. */
final class PowershellFences {
    private static final Pattern FENCE = Pattern.compile("(?ms)^```powershell\\R(.*?)^```");

    private PowershellFences() {}

    static List<String> of(String markdown) {
        List<String> blocks = new ArrayList<>();
        Matcher m = FENCE.matcher(markdown);
        while (m.find()) blocks.add(m.group(1));
        return blocks;
    }

    /** PowerShell does not expand cmd-style {@code %VAR%}, so a fence holding one is a command that fails as written. */
    static void assertNoCmdVariables(String markdown) {
        List<String> blocks = of(markdown);
        assertFalse(blocks.isEmpty(), "page has powershell fences");
        for (String b : blocks) {
            assertFalse(b.contains("%LOCALAPPDATA%"), "%LOCALAPPDATA% inside a powershell fence:\n" + b);
            assertFalse(b.matches("(?s).*%\\w+%.*"), "cmd-style variable inside a powershell fence:\n" + b);
        }
    }
}
