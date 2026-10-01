package dev.vexelray.installer.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Creates (or reuses) the GitHub release for a tag and uploads the staged artifacts to it,
 * replacing assets of the same name. Uses the REST API directly so CI needs no extra tooling.
 */
public final class GithubReleasePublisher {
    private final String apiBase;
    private final String token;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Consumer<String> log;

    public GithubReleasePublisher(String apiBase, String token, Consumer<String> log) {
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.token = token;
        this.log = log;
    }

    public static GithubReleasePublisher github(String token, Consumer<String> log) {
        return new GithubReleasePublisher("https://api.github.com", token, log);
    }

    public void publish(GenerationResult result, String name) {
        if (result.releasesRepo() == null)
            throw new InstallerException("cannot publish: no 'releasesRepo' or 'repo' configured");
        String repo = result.releasesRepo();
        try {
            JsonNode release = find(repo, result.tag());
            if (release == null) {
                ObjectNode body = Json.object();
                body.put("tag_name", result.tag());
                body.put("name", name + " " + result.version());
                body.put("body", result.notes().isBlank() ? name + " " + result.version() : result.notes());
                body.put("prerelease", result.prerelease());
                release = send(request("/repos/" + repo + "/releases").POST(HttpRequest.BodyPublishers.ofString(Json.compact(body))), 201);
                log.accept("created release " + result.tag() + " in " + repo);
            } else {
                log.accept("release " + result.tag() + " already exists in " + repo + "; updating its assets");
            }
            String uploadBase = release.path("upload_url").asText().replaceFirst("\\{.*$", "");
            for (Path asset : result.assets()) {
                String file = asset.getFileName().toString();
                for (JsonNode existing : release.path("assets")) {
                    if (existing.path("name").asText().equals(file)) {
                        send(request("/repos/" + repo + "/releases/assets/" + existing.path("id").asLong()).DELETE(), 204);
                    }
                }
                log.accept("uploading " + file);
                HttpRequest up = HttpRequest.newBuilder(URI.create(uploadBase + "?name=" + URLEncoder.encode(file, StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofFile(asset)).build();
                send(up, 201);
            }
        } catch (IOException e) {
            throw new InstallerException("GitHub request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstallerException("interrupted while publishing", e);
        }
    }

    private JsonNode find(String repo, String tag) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(request("/repos/" + repo + "/releases/tags/" + tag).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) return null;
        if (resp.statusCode() != 200) throw failure(resp);
        return Json.tree(resp.body());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(apiBase + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json");
    }

    private JsonNode send(HttpRequest.Builder b, int expected) throws IOException, InterruptedException {
        return send(b.build(), expected);
    }

    private JsonNode send(HttpRequest req, int expected) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != expected) throw failure(resp);
        return resp.body().isBlank() ? Json.object() : Json.tree(resp.body());
    }

    private static InstallerException failure(HttpResponse<String> resp) {
        String hint = resp.statusCode() == 401 || resp.statusCode() == 403
                ? " (check the token has write access to the repository's contents)" : "";
        return new InstallerException("GitHub answered " + resp.statusCode() + " for "
                + resp.request().method() + " " + resp.request().uri() + hint + ": " + resp.body());
    }

}
