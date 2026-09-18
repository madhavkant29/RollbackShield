package com.rollbackshield.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The control room runs on its own origin and calls the control plane
 * cross-origin, so CORS is load-bearing for the documented local flow.
 * These assert the allow-list is honored in both directions -- an
 * unconfigured origin must never get an Access-Control-Allow-Origin header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CorsConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflightFromConfiguredOriginIsAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/services")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    void preflightFromUnconfiguredOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/services")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
