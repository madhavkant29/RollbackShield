package com.rollbackshield.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The §38 "highest priority E2E" scenario, against the real Spring MVC
 * dispatcher (in-memory persistence, 'local' profile -- LocalDevAuthFilter
 * supplies the principal, so no token handling is needed here): create
 * organization context (via the local-dev seed) -> service -> release ->
 * activate contract -> allow compatible mutation policy is servable ->
 * enqueue work -> rollback -> redeem is fenced -> audit trail is present.
 *
 * NOTE: this test has not been run in the environment that wrote it (no
 * Maven Central access there). Run `mvn test` locally; if anything fails,
 * the assertion message plus the actual JSON response is exactly what's
 * needed to fix it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ReleaseLifecycleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullRollbackScenarioEndToEnd() throws Exception {
        // 1. create service
        String serviceResponse = mockMvc.perform(post("/api/v1/services")
                .contentType("application/json")
                .content("{\"name\":\"checkout\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String serviceId = readField(serviceResponse, "serviceId");

        // 2. create release
        String releaseResponse = mockMvc.perform(post("/api/v1/releases")
                .contentType("application/json")
                .content("{\"serviceId\":\"" + serviceId + "\",\"previousVersionLabel\":\"v1\","
                    + "\"candidateVersionLabel\":\"v2\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andReturn().getResponse().getContentAsString();
        String releaseId = readField(releaseResponse, "releaseId");

        mockMvc.perform(post("/api/v1/releases/" + releaseId + "/prepare"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("PREPARING"));

        mockMvc.perform(post("/api/v1/releases/" + releaseId + "/ready"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("READY"));

        // 3. activate contract -> drives release to PROTECTED_ROLLOUT
        String contractBody = """
            {"rollbackWindowSeconds":7200,"candidateEpochRequiredForAsyncWork":true,
             "rules":[{"type":"ENUM_ALLOWED_VALUES","entity":"Order","field":"status",
                       "previousVersionSupports":["CREATED","PAID","CANCELLED","REFUNDED"]}]}""";
        String contractResponse = mockMvc.perform(post("/api/v1/releases/" + releaseId + "/contracts")
                .contentType("application/json")
                .content(contractBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andReturn().getResponse().getContentAsString();
        String contractId = readField(contractResponse, "contractId");

        mockMvc.perform(get("/api/v1/releases/" + releaseId))
            .andExpect(jsonPath("$.state").value("PROTECTED_ROLLOUT"))
            .andExpect(jsonPath("$.epoch").value(1));

        // 4. the SDK's policy endpoint is servable and contains the rule we set
        mockMvc.perform(get("/api/v1/contracts/" + contractId + "/policy"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rules[0].type").value("ENUM_ALLOWED_VALUES"))
            .andExpect(jsonPath("$.rules[0].previousVersionSupports").isArray());

        // 5. reversibility is REVERSIBLE while protected
        mockMvc.perform(get("/api/v1/releases/" + releaseId + "/reversibility"))
            .andExpect(jsonPath("$.status").value("REVERSIBLE"));

        // 6. enqueue async work under the current epoch
        String jobResponse = mockMvc.perform(post("/api/v1/releases/" + releaseId + "/work")
                .contentType("application/json")
                .content("{\"jobType\":\"ISSUE_PARTIAL_REFUND_WEBHOOK\",\"payload\":\"order-1\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.releaseEpoch").value(1))
            .andReturn().getResponse().getContentAsString();
        String jobId = readField(jobResponse, "jobId");

        // 7. rollback: release transitions all the way to ROLLED_BACK
        mockMvc.perform(post("/api/v1/releases/" + releaseId + "/rollback")
                .contentType("application/json")
                .content("{\"reason\":\"integration test rollback\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("ROLLED_BACK"));

        // 8. the job's epoch is now invalid -- redeem must CANCEL, twice, idempotently
        String redeemBody = "{\"releaseId\":\"" + releaseId + "\",\"releaseEpoch\":1}";
        mockMvc.perform(post("/api/v1/work/" + jobId + "/redeem")
                .contentType("application/json").content(redeemBody))
            .andExpect(jsonPath("$.outcome").value("CANCEL"));
        mockMvc.perform(post("/api/v1/work/" + jobId + "/redeem")
                .contentType("application/json").content(redeemBody))
            .andExpect(jsonPath("$.outcome").value("CANCEL")); // idempotent, not a second decision

        // 9. audit trail records the sequence
        mockMvc.perform(get("/api/v1/releases/" + releaseId + "/audit"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem(
                org.hamcrest.Matchers.hasEntry("action", "ROLLBACK_COMPLETED"))));
    }

    @Test
    void invalidTransitionReturnsStructuredConflict() throws Exception {
        String serviceResponse = mockMvc.perform(post("/api/v1/services")
                .contentType("application/json").content("{\"name\":\"payments\"}"))
            .andReturn().getResponse().getContentAsString();
        String serviceId = readField(serviceResponse, "serviceId");

        String releaseResponse = mockMvc.perform(post("/api/v1/releases")
                .contentType("application/json")
                .content("{\"serviceId\":\"" + serviceId + "\",\"previousVersionLabel\":\"v1\","
                    + "\"candidateVersionLabel\":\"v2\"}"))
            .andReturn().getResponse().getContentAsString();
        String releaseId = readField(releaseResponse, "releaseId");

        // DRAFT cannot go straight to rollback
        mockMvc.perform(post("/api/v1/releases/" + releaseId + "/rollback")
                .contentType("application/json").content("{\"reason\":\"nope\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_RELEASE_TRANSITION"));
    }

    @SuppressWarnings("unchecked")
    private String readField(String json, String field) throws Exception {
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        return (String) map.get(field);
    }
}
