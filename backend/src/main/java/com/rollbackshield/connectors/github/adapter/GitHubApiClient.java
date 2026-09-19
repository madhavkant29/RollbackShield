package com.rollbackshield.connectors.github.adapter;

import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.shared.api.ValidationException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Bounded HTTP transport for the GitHub REST API. All provider HTTP lives
 * here; the connector above it only sees parsed JSON. The base URL is
 * configurable so the same code path is exercised against a local stub in
 * tests, and errors are surfaced with GitHub's own message rather than a
 * fabricated fallback.
 */
public class GitHubApiClient {

    static final String DEFAULT_BASE_URL = "https://api.github.com";

    private final HttpClient http;
    private final String baseUrl;

    public GitHubApiClient() {
        this(DEFAULT_BASE_URL);
    }

    public GitHubApiClient(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String get(ConnectorContext context, String path, String token) {
        HttpRequest request = request(context, path, token);
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw githubError(path, response);
            }
            return response.body();
        } catch (IOException e) {
            throw new GitHubApiException("GitHub request failed for " + path + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubApiException("GitHub request interrupted for " + path);
        }
    }

    public String post(ConnectorContext context, String path, String token, String jsonBody) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw githubError(path, response);
            }
            return response.body();
        } catch (IOException e) {
            throw new GitHubApiException("GitHub request failed for " + path + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubApiException("GitHub request interrupted for " + path);
        }
    }

    /** Fetches raw file content (Accept: raw), or null for 404. */
    public String getRawFile(ConnectorContext context, String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github.raw")
            .header("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = builder.GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() >= 400) {
                throw githubError(path, response);
            }
            return response.body();
        } catch (IOException e) {
            throw new GitHubApiException("GitHub raw fetch failed for " + path + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubApiException("GitHub raw fetch interrupted for " + path);
        }
    }

    public String installationToken(ConnectorContext context, String appJwt, String installationId) {
        return post(context, "/app/installations/" + encode(installationId) + "/access_tokens", appJwt, "{}");
    }

    private HttpRequest request(ConnectorContext context, String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.GET().build();
    }

    private static GitHubApiException githubError(String path, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        String message = body.length() > 400 ? body.substring(0, 400) : body;
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return new GitHubApiException("GitHub rejected the credentials for " + path
                + " (HTTP " + response.statusCode() + "): " + message);
        }
        return new GitHubApiException("GitHub returned HTTP " + response.statusCode()
            + " for " + path + ": " + message);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static String tokenFor(ConnectorContext context) {
        CredentialMaterial material = context.credentials();
        if (material instanceof CredentialMaterial.GitHubToken token) {
            return token.token();
        }
        throw new ValidationException("CREDENTIAL_UNAVAILABLE",
            "GitHub connector requires a GitHub token or app credentials",
            Map.of("credentialKind", material.getClass().getSimpleName()));
    }

    /** Raised for any non-2xx provider response or transport failure. */
    public static class GitHubApiException extends RuntimeException {
        public GitHubApiException(String message) {
            super(message);
        }
    }
}
