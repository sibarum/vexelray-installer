package dev.vexelray.installer.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the publisher against a fake GitHub API. */
class GithubReleasePublisherTest {
    @TempDir Path tmp;
    HttpServer server;
    String base;
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    volatile boolean releaseExists;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            String key = ex.getRequestMethod() + " " + ex.getRequestURI();
            calls.add(key);
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.getRequestBody().readAllBytes();
            String body = "{}";
            int status = 200;
            if (key.equals("GET /repos/o/r/releases/tags/v1.0.0")) {
                if (releaseExists) {
                    body = "{\"upload_url\":\"" + base + "/upload/{?name,label}\",\"assets\":[{\"id\":7,\"name\":\"app.exe\"}]}";
                } else status = 404;
            } else if (key.equals("POST /repos/o/r/releases")) {
                status = 201;
                body = "{\"upload_url\":\"" + base + "/upload/{?name,label}\",\"assets\":[]}";
            } else if (key.equals("DELETE /repos/o/r/releases/assets/7")) {
                status = 204;
                body = "";
            } else if (key.startsWith("POST /upload/")) {
                status = 201;
            } else {
                status = 500;
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, status == 204 ? -1 : b.length);
            if (status != 204) ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    GenerationResult result() throws IOException {
        Path exe = tmp.resolve("app.exe");
        Path jar = tmp.resolve("tool.jar");
        Files.writeString(exe, "x");
        Files.writeString(jar, "y");
        return new GenerationResult("1.0.0", "1.0.0", "v1.0.0", "o/r", "m", "c", List.of(exe, jar), List.of(), "the notes", false);
    }

    @Test
    void createsTheReleaseAndUploadsEveryAsset() throws IOException {
        List<String> log = new ArrayList<>();
        GithubReleasePublisher p = new GithubReleasePublisher(base, "tok", log::add);
        p.publish(result(), "MyApp");
        assertEquals(List.of(
                "GET /repos/o/r/releases/tags/v1.0.0",
                "POST /repos/o/r/releases",
                "POST /upload/?name=app.exe",
                "POST /upload/?name=tool.jar"), calls);
        assertTrue(auth.stream().allMatch("Bearer tok"::equals));
    }

    @Test
    void replacesAnExistingAssetOfTheSameName() throws IOException {
        releaseExists = true;
        new GithubReleasePublisher(base, "tok", s -> { }).publish(result(), "MyApp");
        assertEquals(List.of(
                "GET /repos/o/r/releases/tags/v1.0.0",
                "DELETE /repos/o/r/releases/assets/7",
                "POST /upload/?name=app.exe",
                "POST /upload/?name=tool.jar"), calls);
    }

    @Test
    void anApiErrorSurfacesWithTheStatus() throws IOException {
        server.removeContext("/");
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        InstallerException e = assertThrows(InstallerException.class,
                () -> new GithubReleasePublisher(base, "bad", s -> { }).publish(result(), "MyApp"));
        assertTrue(e.getMessage().contains("401"));
    }
}
