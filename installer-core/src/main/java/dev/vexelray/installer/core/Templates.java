package dev.vexelray.installer.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads templates from the classpath and fills their {@code {{TOKEN}}} placeholders. */
final class Templates {
    private Templates() {}

    private static final Pattern TOKEN = Pattern.compile("\\{\\{[A-Z_]+}}");

    static String load(String resource) {
        String path = "/templates/" + resource;
        try (InputStream in = Templates.class.getResourceAsStream(path)) {
            if (in == null) throw new InstallerException("missing template " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InstallerException("cannot read template " + path, e);
        }
    }

    /** Fills every token; a token left over (a typo in a template) is an error, not silent output. */
    static String render(String resource, Map<String, String> vars) {
        String text = load(resource);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        Matcher m = TOKEN.matcher(text);
        if (m.find()) throw new InstallerException("unreplaced token " + m.group() + " in template " + resource);
        return text;
    }

    static String lf(String text) { return text.replace("\r\n", "\n"); }
}
