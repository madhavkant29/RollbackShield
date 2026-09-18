package com.rollbackshield.worker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Standalone demo worker process (§16, §32): polls the control plane's
 * GET /api/v1/work/poll (which proxies SQS in the 'aws' profile, or the
 * in-memory queue locally), then redeems each job via
 * POST /api/v1/work/{jobId}/redeem before it would perform any irreversible
 * side effect. This is a SEPARATE deployable from both backend and
 * demo-app -- in AWS it becomes its own ECS task or Lambda consuming SQS
 * directly, decoupled from the control plane's web tier.
 *
 * Zero dependencies beyond the JDK, matching the SDK's own dependency-free
 * philosophy for anything that ships as a standalone runtime component.
 */
public final class WorkerMain {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String SERVICE_CREDENTIAL_HEADER = "X-RollbackShield-Service-Credential";

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        String serviceCredential = System.getenv().getOrDefault(
            "ROLLBACKSHIELD_SERVICE_CREDENTIAL", "local-dev-service-credential");
        System.out.println("demo-worker polling " + baseUrl + "/api/v1/work/poll every 2s (Ctrl+C to stop)");

        while (true) {
            String body = get(baseUrl + "/api/v1/work/poll?max=10", serviceCredential);
            // Minimal, dependency-free scan for job objects; the demo-app's
            // ControlPlaneClient uses the SDK's MinimalJson for the same
            // purpose -- kept separate here since this module intentionally
            // has zero dependencies, not even on sdk-java.
            for (String jobId : extractField(body, "jobId")) {
                System.out.println("received job " + jobId + " -- would call /work/" + jobId
                    + "/redeem with this job's releaseId+releaseEpoch before any irreversible action");
            }
            Thread.sleep(2000);
        }
    }

    private static String get(String url, String serviceCredential) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header(SERVICE_CREDENTIAL_HEADER, serviceCredential)
            .GET().build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    /** Tiny scan for `"field":"value"` occurrences -- not a general JSON parser, deliberately. */
    private static java.util.List<String> extractField(String json, String field) {
        java.util.List<String> values = new java.util.ArrayList<>();
        String needle = "\"" + field + "\":\"";
        int idx = 0;
        while ((idx = json.indexOf(needle, idx)) != -1) {
            int start = idx + needle.length();
            int end = json.indexOf('"', start);
            values.add(json.substring(start, end));
            idx = end;
        }
        return values;
    }
}
