package com.rollbackshield.demo.run;

import com.rollbackshield.sdk.internal.MinimalJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Talks to the real RollbackShield control-plane REST API. This replaces the
 * earlier shortcut where the demo called backend AuditTrail /
 * ReversibilityEvaluator classes directly in-process -- every control-plane
 * interaction below crosses the actual HTTP boundary, the same one a real
 * external application would use. Auth: under the 'local' Spring profile the
 * backend's LocalDevAuthFilter accepts any request with no token, so no
 * credential handling is needed here for local runs.
 */
final class ControlPlaneClient {

    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;

    ControlPlaneClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    Map<String, Object> post(String path, String jsonBody) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    Map<String, Object> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5))
            .GET());
    }

    /** For endpoints that return a JSON array (e.g. audit history). */
    java.util.List<Object> getList(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            checkStatus(path, response);
            return MinimalJson.parseObject("{\"_\":" + response.body() + "}").get("_") instanceof java.util.List<?> l
                ? (java.util.List<Object>) l : java.util.List.of();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Request failed: " + path, e);
        }
    }

    private Map<String, Object> send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            checkStatus(builder.build().uri().toString(), response);
            return MinimalJson.parseObject(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Request failed: " + builder.build().uri(), e);
        }
    }

    private void checkStatus(String path, HttpResponse<String> response) {
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("Control plane returned HTTP " + response.statusCode()
                + " for " + path + ": " + response.body());
        }
    }
}
