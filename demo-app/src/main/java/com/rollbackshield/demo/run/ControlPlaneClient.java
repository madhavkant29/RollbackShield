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
 * external application would use.
 *
 * Sends the shared service credential on every call: the worker endpoints
 * (poll/redeem) require it, and the tenant endpoints simply ignore an
 * unknown header. Under the 'local' profile the credential defaults to the
 * same dev value the backend uses.
 */
final class ControlPlaneClient {

    static final String SERVICE_CREDENTIAL_HEADER = "X-RollbackShield-Service-Credential";

    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String serviceCredential;

    ControlPlaneClient(String baseUrl, String serviceCredential) {
        this.baseUrl = baseUrl;
        this.serviceCredential = serviceCredential;
    }

    Map<String, Object> post(String path, String jsonBody) {
        return send(withCredential(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json"))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    Map<String, Object> get(String path) {
        return send(withCredential(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5)))
            .GET());
    }

    /** For endpoints that return a JSON array (e.g. audit history). */
    java.util.List<Object> getList(String path) {
        try {
            HttpRequest request = withCredential(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5)))
                .GET().build();
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

    private HttpRequest.Builder withCredential(HttpRequest.Builder builder) {
        if (serviceCredential != null && !serviceCredential.isBlank()) {
            builder.header(SERVICE_CREDENTIAL_HEADER, serviceCredential);
        }
        return builder;
    }

    private Map<String, Object> send(HttpRequest.Builder builder) {
        try {
            HttpRequest request = builder.build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            checkStatus(request.uri().toString(), response);
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
