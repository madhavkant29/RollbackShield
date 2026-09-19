package com.rollbackshield.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The integrations API over the real Spring MVC dispatcher with the
 * in-memory profile: create -> connection test (a real directory read via
 * the FLYWAY connector) -> sync -> list -> resources -> disconnect ->
 * delete, plus the stable error codes for unsupported connectors and
 * invalid credential references. This is the HTTP-level counterpart to
 * IntegrationApplicationServiceTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class IntegrationsApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path migrationDirectory;

    @Test
    void integrationLifecycleOverHttp() throws Exception {
        Files.writeString(migrationDirectory.resolve("V1__init.sql"), "CREATE TABLE demo (id uuid);");

        String createBody = objectMapper.writeValueAsString(Map.of(
            "name", "it-flyway",
            "type", "FLYWAY",
            "endpoint", migrationDirectory.toString(),
            "credential", Map.of("kind", "NONE"),
            "configuration", Map.of("directory", migrationDirectory.toString())));

        String created = mockMvc.perform(post("/api/v1/integrations")
                .contentType("application/json").content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.connectionState").value("CONNECTING"))
            .andExpect(jsonPath("$.healthState").value("UNKNOWN"))
            .andExpect(jsonPath("$.lastSuccessfulSyncAt").doesNotExist())
            .andExpect(jsonPath("$.capabilities[0]").value("DATABASE_MIGRATION_ANALYSIS"))
            .andReturn().getResponse().getContentAsString();
        String integrationId = readField(created, "integrationId");

        mockMvc.perform(get("/api/v1/integrations/" + integrationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("it-flyway"));

        // The connection test is a real directory read, not configuration inspection.
        mockMvc.perform(post("/api/v1/integrations/" + integrationId + "/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("Migration directory readable")));

        mockMvc.perform(post("/api/v1/integrations/" + integrationId + "/sync"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.discoveredCount").value(0))
            .andExpect(jsonPath("$.errorCount").value(0));

        mockMvc.perform(get("/api/v1/integrations/" + integrationId))
            .andExpect(jsonPath("$.connectionState").value("CONNECTED"))
            .andExpect(jsonPath("$.healthState").value("HEALTHY"))
            .andExpect(jsonPath("$.lastSuccessfulSyncAt").exists());

        mockMvc.perform(get("/api/v1/integrations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem(
                org.hamcrest.Matchers.hasEntry("integrationId", integrationId))));

        mockMvc.perform(get("/api/v1/integrations/" + integrationId + "/resources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(post("/api/v1/integrations/" + integrationId + "/disconnect"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connectionState").value("DISCONNECTED"));

        mockMvc.perform(delete("/api/v1/integrations/" + integrationId))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/integrations/" + integrationId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("INTEGRATION_NOT_FOUND"));
    }

    @Test
    void unsupportedConnectorsAndInvalidCredentialsAreRejectedWithStableCodes() throws Exception {
        mockMvc.perform(post("/api/v1/integrations")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "gitlab", "type", "GITLAB", "endpoint", "https://gitlab.example",
                    "credential", Map.of("kind", "NONE")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CONNECTOR_NOT_IMPLEMENTED"));

        mockMvc.perform(post("/api/v1/integrations")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "aws-wrong-credential", "type", "AWS", "endpoint", "us-east-1",
                    "credential", Map.of("kind", "GITHUB_TOKEN", "secretReference", "SOME_VAR")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIAL_REFERENCE"));

        mockMvc.perform(post("/api/v1/integrations")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "aws-missing-role", "type", "AWS", "endpoint", "us-east-1",
                    "credential", Map.of("kind", "AWS_ASSUME_ROLE")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIAL_REFERENCE"));
    }

    @Test
    void unknownIntegrationIdIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/integrations/00000000-0000-0000-0000-000000000042"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("INTEGRATION_NOT_FOUND"));
    }

    @SuppressWarnings("unchecked")
    private String readField(String json, String field) throws Exception {
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        return (String) map.get(field);
    }
}
