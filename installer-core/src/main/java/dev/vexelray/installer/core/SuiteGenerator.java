package dev.vexelray.installer.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a suite's one-line installer and its install page. The suite's script installs nothing itself:
 * it fetches each member's published manifest, downloads that member's own {@code install.ps1}, checks it
 * against the SHA-256 the manifest records, and runs it. So every member is installed exactly as its own
 * one-liner would install it, and what a member's install does is decided in one place, its own config.
 *
 * <p>Needs no artifacts and no network: it reads the members' {@code installer.json} files, which is
 * enough to know their names and where their manifests are published.
 */
public final class SuiteGenerator {

    /** One member, read from its installer.json. */
    private record Resolved(InstallerConfig config, SuiteConfig.Member member, String manifestUrl) {}

    /**
     * @param baseDir member {@code config} paths are resolved against this (the suite config's folder)
     * @param baseUrl overrides the public URL of {@code outDir}; null for raw.githubusercontent.com
     * @return the command users paste
     */
    public String generate(SuiteConfig s, Path baseDir, Path outDir, String baseUrl) {
        try {
            return run(s, baseDir, outDir, baseUrl);
        } catch (IOException e) {
            throw new InstallerException("I/O error: " + e.getMessage(), e);
        }
    }

    private String run(SuiteConfig s, Path baseDir, Path outDir, String baseUrlOverride) throws IOException {
        if (s == null) throw new InstallerException("no suite config");
        if (!InstallerGenerator.validId(s.id))
            throw new InstallerException("'id' is required: lowercase letters, digits, . _ -");
        if (InstallerConfig.blank(s.name)) throw new InstallerException("'name' is required");
        if (s.members == null || s.members.isEmpty()) throw new InstallerException("a suite needs at least one member");
        String baseUrl = InstallerGenerator.baseUrl(s.repo, s.branchOrDefault(), s.repoPathTrimmed(), baseUrlOverride);
        String installUrl = baseUrl + "/install.ps1";

        List<Resolved> members = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (SuiteConfig.Member m : s.members) {
            if (m == null || InstallerConfig.blank(m.config))
                throw new InstallerException("every member needs a 'config' (the path to its installer.json)");
            Path p = Path.of(m.config);
            if (!p.isAbsolute()) p = baseDir.resolve(p);
            if (!Files.isRegularFile(p)) throw new InstallerException("member config not found: " + p);
            InstallerConfig c = Json.read(Files.readString(p, StandardCharsets.UTF_8), InstallerConfig.class);
            if (!InstallerGenerator.validId(c.id)) throw new InstallerException(p + " has no valid 'id'");
            if (InstallerConfig.blank(c.name)) throw new InstallerException(p + " has no 'name'");
            if (c.windows == null) throw new InstallerException(c.id + " has no 'windows' section; a suite installs on Windows only for now");
            if (!ids.add(c.id)) throw new InstallerException("the suite lists '" + c.id + "' twice");
            String manifestUrl = !InstallerConfig.blank(m.manifestUrl) ? m.manifestUrl
                    : InstallerGenerator.baseUrl(c.repo, c.branchOrDefault(), c.repoPathTrimmed(), null) + "/manifest.json";
            members.add(new Resolved(c, m, manifestUrl));
        }

        ObjectNode cfg = Json.object();
        cfg.put("name", s.name);
        ArrayNode list = cfg.putArray("members");
        for (Resolved r : members) {
            ObjectNode n = list.addObject();
            n.put("id", r.config.id);
            n.put("name", r.config.name);
            n.put("manifestUrl", r.manifestUrl);
            n.put("registryDir", WindowsTarget.registryDir(r.config.windows));
        }
        String idList = String.join(", ", members.stream().map(r -> r.config.id).toList());

        Files.createDirectories(outDir);
        String script = Templates.render("suite/install.ps1", Map.of(
                "NAME", s.name,
                "INSTALL_URL", installUrl,
                "IDS", idList,
                "CONFIG_JSON", Json.compact(cfg).replace("'", "''")));
        InstallerGenerator.writeAscii(outDir.resolve("install.ps1"), script);

        List<String> table = new ArrayList<>();
        table.add("| Application | What it is | Id | Installed to |");
        table.add("| --- | --- | --- | --- |");
        for (Resolved r : members) {
            String name = InstallerConfig.blank(r.member.page) ? r.config.name : "[" + r.config.name + "](" + r.member.page + ")";
            table.add("| " + name + " | " + r.config.descriptionOrDefault() + " | `" + r.config.id + "` | `"
                    + WindowsTarget.installDir(r.config, r.config.windows) + "` |");
        }
        Resolved first = members.get(0);
        String readme = Templates.render("suite/README.md", Map.of(
                "NAME", s.name,
                "DESCRIPTION", InstallerConfig.blank(s.description) ? "" : s.description.trim() + "\n",
                "INSTALL_COMMAND", "irm " + installUrl + " | iex",
                "INSTALL_URL", installUrl,
                "MEMBER_TABLE", String.join("\n", table),
                "EXAMPLE_IDS", members.size() > 1 ? first.config.id + "," + members.get(1).config.id : first.config.id,
                "EXAMPLE_DIR", WindowsTarget.installDir(first.config, first.config.windows),
                "EXAMPLE_ID", first.config.id,
                "REGISTRY_DIR", WindowsTarget.registryDir(first.config.windows)));
        Path readmePath = outDir.resolve("README.md");
        if (Files.exists(readmePath)
                && !Files.readString(readmePath, StandardCharsets.UTF_8).contains(InstallerGenerator.README_MARKER)) {
            readmePath = outDir.resolve("INSTALL.md");
        }
        InstallerGenerator.writeUtf8(readmePath, readme);
        return "irm " + installUrl + " | iex";
    }
}
