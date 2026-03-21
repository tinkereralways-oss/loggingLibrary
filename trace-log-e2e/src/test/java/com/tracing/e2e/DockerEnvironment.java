package com.tracing.e2e;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages Docker Compose lifecycle for E2E tests.
 * Starts containers before tests and tears them down after.
 */
final class DockerEnvironment {

    private static final String COMPOSE_FILE = findComposeFile();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_HEALTH_RETRIES = 40;
    private static final long HEALTH_RETRY_INTERVAL_MS = 1500;

    private final HttpClient httpClient;

    DockerEnvironment() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    void start() throws Exception {
        System.out.println("[e2e] Building and starting containers...");
        exec("docker", "compose", "-f", COMPOSE_FILE, "up", "-d", "--build", "--wait");
        waitForHealth("http://localhost:8085/health", "trace-log-app");
        waitForHealth("http://localhost:8086/health", "trace-log-app-sampled");
        System.out.println("[e2e] All containers healthy.");
    }

    void stop() throws Exception {
        System.out.println("[e2e] Stopping containers...");
        exec("docker", "compose", "-f", COMPOSE_FILE, "down", "--volumes", "--remove-orphans");
    }

    List<String> getLogs(String service) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "compose", "-f", COMPOSE_FILE, "logs", "--no-log-prefix", service);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        process.waitFor(30, TimeUnit.SECONDS);
        return lines;
    }

    HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String url, String headerName, String headerValue) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header(headerName, headerValue)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String url, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void waitForHealth(String healthUrl, String name) throws Exception {
        System.out.println("[e2e] Waiting for " + name + " at " + healthUrl + "...");
        for (int i = 1; i <= MAX_HEALTH_RETRIES; i++) {
            try {
                HttpResponse<String> response = get(healthUrl);
                if (response.statusCode() == 200 && response.body().contains("UP")) {
                    System.out.println("[e2e] " + name + " is healthy (attempt " + i + ")");
                    return;
                }
            } catch (Exception e) {
                // not ready yet
            }
            Thread.sleep(HEALTH_RETRY_INTERVAL_MS);
        }
        throw new RuntimeException(name + " did not become healthy within timeout");
    }

    private void exec(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
        }
    }

    private static String findComposeFile() {
        // Try relative paths from common working directories
        for (String candidate : List.of(
                "../docker-compose.e2e.yml",
                "docker-compose.e2e.yml",
                "trace-log-e2e/../docker-compose.e2e.yml")) {
            File f = new File(candidate);
            if (f.exists()) {
                return f.getAbsolutePath();
            }
        }
        // Fallback: walk up from current dir to find it
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            File candidate = dir.resolve("docker-compose.e2e.yml").toFile();
            if (candidate.exists()) {
                return candidate.getAbsolutePath();
            }
            dir = dir.getParent();
        }
        throw new RuntimeException("Cannot find docker-compose.e2e.yml from " + Path.of("").toAbsolutePath());
    }
}
