package com.rollbackshield.sdk;

import com.rollbackshield.sdk.internal.MinimalJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fetches a policy snapshot from the RollbackShield control plane's
 * {@code GET /api/v1/contracts/{contractId}/policy} endpoint.
 *
 * This is the ONLY network call anywhere in the SDK, and it is only ever
 * invoked from {@link PolicyCache}'s scheduled background refresh -- never
 * from {@link RollbackGuard#evaluate}. The mutation hot path always reads
 * whatever snapshot is already cached in memory.
 */
public final class HttpPolicySource implements PolicySource {

    /**
     * Header carrying the shared service credential. The control plane
     * requires it on the policy endpoint; a blank/null credential here means
     * no header is sent and the fetch will be rejected once the server has
     * a credential configured (it fails closed).
     */
    public static final String SERVICE_CREDENTIAL_HEADER = "X-RollbackShield-Service-Credential";

    private final HttpClient client;
    private final String baseUrl;
    private final Duration timeout;
    private final String serviceCredential;

    public HttpPolicySource(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(5), null);
    }

    public HttpPolicySource(String baseUrl, Duration timeout) {
        this(baseUrl, timeout, null);
    }

    public HttpPolicySource(String baseUrl, String serviceCredential) {
        this(baseUrl, Duration.ofSeconds(5), serviceCredential);
    }

    public HttpPolicySource(String baseUrl, Duration timeout, String serviceCredential) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.serviceCredential = serviceCredential;
        this.client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .build();
    }

    @Override
    public PolicySnapshot fetch(String contractId) throws PolicyFetchException {
        URI uri = URI.create(baseUrl + "/api/v1/contracts/" + contractId + "/policy");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/json");
        if (serviceCredential != null && !serviceCredential.isBlank()) {
            builder.header(SERVICE_CREDENTIAL_HEADER, serviceCredential);
        }
        HttpRequest request = builder.GET().build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new PolicyFetchException(
                    "Policy fetch failed with HTTP " + response.statusCode() + " for contract " + contractId,
                    null);
            }
            return parse(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PolicyFetchException("Policy fetch failed for contract " + contractId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private PolicySnapshot parse(String json) throws PolicyFetchException {
        try {
            Map<String, Object> root = MinimalJson.parseObject(json);
            String contractId = (String) root.get("contractId");
            int contractVersion = ((Double) root.get("contractVersion")).intValue();
            long policyVersion = ((Double) root.get("policyVersion")).longValue();
            boolean epochRequired = Boolean.TRUE.equals(root.get("candidateEpochRequiredForAsyncWork"));

            List<Object> rawRules = (List<Object>) root.getOrDefault("rules", List.of());
            List<SdkCompatibilityRule> rules = new ArrayList<>();
            for (Object rawRule : rawRules) {
                rules.add(parseRule((Map<String, Object>) rawRule));
            }

            return new PolicySnapshot(contractId, contractVersion, policyVersion, epochRequired,
                Instant.now(), rules);
        } catch (RuntimeException e) {
            throw new PolicyFetchException("Malformed policy response", e);
        }
    }

    @SuppressWarnings("unchecked")
    private SdkCompatibilityRule parseRule(Map<String, Object> rule) {
        String type = (String) rule.get("type");
        String entity = (String) rule.get("entity");
        String field = (String) rule.get("field");

        return switch (type) {
            case "ENUM_ALLOWED_VALUES" -> new SdkCompatibilityRule.EnumAllowedValues(
                entity, field, Set.copyOf((List<String>) (List<?>) rule.get("previousVersionSupports")));
            case "NULLABILITY" -> new SdkCompatibilityRule.Nullability(
                entity, field, Boolean.TRUE.equals(rule.get("previousVersionAllowsNull")));
            case "NUMERIC_RANGE" -> new SdkCompatibilityRule.NumericRange(
                entity, field, (Double) rule.get("min"), (Double) rule.get("max"));
            case "REQUIRED_FIELD" -> new SdkCompatibilityRule.RequiredField(entity, field);
            case "FORBIDDEN_VALUE" -> new SdkCompatibilityRule.ForbiddenValue(
                entity, field, Set.copyOf((List<String>) (List<?>) rule.get("forbiddenValues")));
            default -> throw new IllegalArgumentException("Unknown rule type in policy response: " + type);
        };
    }
}
