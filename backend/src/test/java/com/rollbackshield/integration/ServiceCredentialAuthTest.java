package com.rollbackshield.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The worker endpoints and the SDK policy fetch were previously open inside
 * a trusted network (see SECURITY_ARCHITECTURE.md's old gaps). They now
 * require the shared service credential; tenant endpoints keep working with
 * the normal user principal and are unaffected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ServiceCredentialAuthTest {

    private static final String HEADER = "X-RollbackShield-Service-Credential";
    private static final String CREDENTIAL = "local-dev-service-credential";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pollRequiresTheServiceCredential() throws Exception {
        mockMvc.perform(get("/api/v1/work/poll"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_SERVICE_CREDENTIAL"));

        mockMvc.perform(get("/api/v1/work/poll").header(HEADER, "wrong-credential"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/work/poll").header(HEADER, CREDENTIAL))
            .andExpect(status().isOk());
    }

    @Test
    void redeemRequiresTheServiceCredential() throws Exception {
        String body = "{\"releaseId\":\"" + UUID.randomUUID() + "\",\"releaseEpoch\":1}";

        mockMvc.perform(post("/api/v1/work/" + UUID.randomUUID() + "/redeem")
                .contentType("application/json").content(body))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/work/" + UUID.randomUUID() + "/redeem")
                .header(HEADER, CREDENTIAL)
                .contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("EXECUTE"));
    }

    @Test
    void policyFetchRequiresTheServiceCredential() throws Exception {
        mockMvc.perform(get("/api/v1/contracts/" + UUID.randomUUID() + "/policy"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_SERVICE_CREDENTIAL"));
    }

    @Test
    void tenantEndpointsStillUseTheUserPrincipalAndIgnoreTheServiceHeader() throws Exception {
        mockMvc.perform(get("/api/v1/services"))
            .andExpect(status().isOk());
    }
}
