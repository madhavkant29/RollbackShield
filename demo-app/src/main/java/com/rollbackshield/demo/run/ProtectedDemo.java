package com.rollbackshield.demo.run;

import com.rollbackshield.demo.order.Order;
import com.rollbackshield.demo.order.OrderStoreV1;
import com.rollbackshield.demo.order.OrderStoreV2;
import com.rollbackshield.sdk.EnforcementConfig;
import com.rollbackshield.sdk.HttpPolicySource;
import com.rollbackshield.sdk.MutationDecision;
import com.rollbackshield.sdk.MutationRequest;
import com.rollbackshield.sdk.PolicyCache;
import com.rollbackshield.sdk.RollbackGuard;
import com.rollbackshield.sdk.TelemetryBuffer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The protected scenario, now driven through the REAL Spring Boot control
 * plane over HTTP (Phase F) instead of calling backend domain classes
 * in-process. Requires the backend running locally first:
 *
 *   cd backend && mvn spring-boot:run
 *
 * The one thing that is deliberately NOT an HTTP call is the mutation
 * compatibility check itself: RollbackGuard.evaluate() below runs entirely
 * against the SDK's local, previously-fetched PolicySnapshot. The only
 * network call in this whole flow that sits anywhere near enforcement is
 * PolicyCache.initialize()'s one-time background-style fetch, called once,
 * before any mutation is evaluated -- never per mutation.
 */
public final class ProtectedDemo {

    private static final String BASE_URL =
        System.getenv().getOrDefault("ROLLBACKSHIELD_CONTROL_PLANE_URL", "http://localhost:8080");

    public static void main(String[] args) {
        ControlPlaneClient controlPlane = new ControlPlaneClient(BASE_URL);

        Map<String, Order> backingStore = new HashMap<>();
        OrderStoreV2 v2Store = new OrderStoreV2(backingStore);
        OrderStoreV1 v1Store = new OrderStoreV1(backingStore);
        String orderId = "order-protected-1";

        System.out.println("[1] creating service + release via the real control-plane API");
        String serviceId = (String) controlPlane.post("/api/v1/services",
            "{\"name\":\"checkout\"}").get("serviceId");

        String releaseId = (String) controlPlane.post("/api/v1/releases",
            json("serviceId", serviceId, "previousVersionLabel", "v1", "candidateVersionLabel", "v2"))
            .get("releaseId");

        controlPlane.post("/api/v1/releases/" + releaseId + "/prepare", "{}");
        controlPlane.post("/api/v1/releases/" + releaseId + "/ready", "{}");
        System.out.println("    release " + releaseId + " is READY");

        System.out.println("[2] activating the rollback contract (this moves the release to PROTECTED_ROLLOUT)");
        String rulesJson = """
            [{"type":"ENUM_ALLOWED_VALUES","entity":"Order","field":"status",
              "previousVersionSupports":["CREATED","PAID","CANCELLED","REFUNDED"]}]""";
        Map<String, Object> contractResponse = controlPlane.post("/api/v1/releases/" + releaseId + "/contracts",
            "{\"rollbackWindowSeconds\":7200,\"candidateEpochRequiredForAsyncWork\":true,\"rules\":"
                + rulesJson + "}");
        String contractId = (String) contractResponse.get("contractId");
        System.out.println("    contract " + contractId + " active");

        System.out.println("[3] SDK fetches the policy ONCE over real HTTP, then evaluates locally from here on");
        RollbackGuard guard = buildGuard(contractId);

        MutationDecision blocked = guard.evaluate(
            new MutationRequest("Order", "status", "PARTIALLY_REFUNDED"));
        System.out.println("    PARTIALLY_REFUNDED -> " + blocked.decision() + " (" + blocked.reasonCode() + ")");
        if (blocked.decision() != MutationDecision.Decision.BLOCK) {
            v2Store.write(orderId, "PARTIALLY_REFUNDED");
        } else {
            System.out.println("    v2 store write SKIPPED -- mutation never reached persistence");
        }

        MutationDecision allowed = guard.evaluate(new MutationRequest("Order", "status", "PAID"));
        System.out.println("    PAID -> " + allowed.decision());
        if (allowed.decision() == MutationDecision.Decision.ALLOW) {
            v2Store.write(orderId, "PAID");
        }

        System.out.println("[4] enqueueing async work through the control plane");
        Map<String, Object> jobResponse = controlPlane.post("/api/v1/releases/" + releaseId + "/work",
            json("jobType", "ISSUE_PARTIAL_REFUND_WEBHOOK", "payload", orderId));
        String jobId = (String) jobResponse.get("jobId");
        long releaseEpoch = ((Number) jobResponse.get("releaseEpoch")).longValue();
        System.out.println("    job " + jobId + " queued at epoch " + releaseEpoch);

        System.out.println("[5] rolling back through the control plane's real rollback workflow");
        controlPlane.post("/api/v1/releases/" + releaseId + "/rollback",
            "{\"reason\":\"production bug discovered\"}");

        System.out.println("[6] the worker redeems the job -- twice, modeling at-least-once delivery");
        String redeemBody = json("releaseId", releaseId, "releaseEpoch", String.valueOf(releaseEpoch));
        String firstOutcome = (String) controlPlane.post("/api/v1/work/" + jobId + "/redeem", redeemBody)
            .get("outcome");
        String secondOutcome = (String) controlPlane.post("/api/v1/work/" + jobId + "/redeem", redeemBody)
            .get("outcome");
        System.out.println("    first redeem:  " + firstOutcome);
        System.out.println("    second redeem: " + secondOutcome + " (idempotent)");

        System.out.println("[7] v1 reads the order after rollback:");
        Order recovered = v1Store.read(orderId);
        System.out.println("    v1 read succeeded: status = " + recovered.status());

        System.out.println();
        System.out.println("REVERSIBILITY REPORT (from the real control plane)");
        Map<String, Object> reversibility = controlPlane.get("/api/v1/releases/" + releaseId + "/reversibility");
        System.out.println("  status: " + reversibility.get("status"));

        System.out.println();
        System.out.println("AUDIT TRAIL (from the real control plane)");
        List<Object> auditEvents = controlPlane.getList("/api/v1/releases/" + releaseId + "/audit");
        for (Object rawEvent : auditEvents) {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) rawEvent;
            System.out.println("  " + event.get("timestamp") + "  " + event.get("action")
                + "  target=" + event.get("target"));
        }
    }

    private static RollbackGuard buildGuard(String contractId) {
        HttpPolicySource source = new HttpPolicySource(BASE_URL);
        EnforcementConfig config = EnforcementConfig.enforceFailClosed();
        PolicyCache cache = new PolicyCache(contractId, source, config);
        cache.initialize(); // the one real network call in this whole method, done once, up front
        return new RollbackGuard(cache, config, new TelemetryBuffer(64));
    }

    private static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(kv[i]).append("\":\"").append(kv[i + 1]).append("\"");
        }
        return sb.append("}").toString();
    }
}
