package com.rollbackshield.connectors.github.adapter;

import com.rollbackshield.integrations.domain.ConnectionTestResult;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.MigrationFile;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.RepositoryInspection;
import com.rollbackshield.shared.domain.OrganizationId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the GitHub connector over real HTTP against a local stub server.
 * The stub validates the Authorization header (including verifying the RS256
 * signature of the GitHub App JWT with the matching public key), so token
 * minting and request construction are genuinely exercised -- only GitHub's
 * servers are replaced.
 */
class GitHubConnectorTest {

    private HttpServer server;
    private String baseUrl;
    private GitHubConnector connector;
    private ConnectorContext tokenContext;
    private ConnectorContext appContext;
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        connector = new GitHubConnector(new GitHubApiClient(baseUrl));
        tokenContext = context(new CredentialMaterial.GitHubToken("ghp_test_token"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void connectionTestSucceedsAgainstARealUserResponse() {
        stub("/user", exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            return json(200, "{\"login\":\"acme-deployer\",\"type\":\"User\"}");
        });

        ConnectionTestResult result = connector.testConnection(tokenContext);

        assertTrue(result.success());
        assertEquals("ghp_test_token", lastAuthorization.get().replace("Bearer ", ""));
        assertEquals("acme-deployer", result.details().get("login"));
    }

    @Test
    void connectionTestReportsAuthenticationFailureHonestly() {
        stub("/user", exchange -> json(401, "{\"message\":\"Bad credentials\"}"));

        ConnectionTestResult result = connector.testConnection(tokenContext);

        assertFalse(result.success());
        assertTrue(result.message().contains("Bad credentials"));
    }

    @Test
    void discoversRepositoriesFromTheInstallationListing() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String privateKeyPem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        appContext = context(new CredentialMaterial.GitHubAppCredentials("12345", "67890", privateKeyPem));

        stub("/app/installations/67890/access_tokens", exchange -> {
            String jwt = exchange.getRequestHeaders().getFirst("Authorization").replace("Bearer ", "");
            assertTrue(verifyJwt(jwt, keyPair), "installation token request must carry a valid RS256 JWT");
            String[] parts = jwt.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            assertTrue(payload.contains("\"iss\":\"12345\""));
            return json(201, "{\"token\":\"ghs_installation_token\","
                + "\"expires_at\":\"" + java.time.Instant.now().plusSeconds(3600) + "\"}");
        });
        stub("/installation/repositories", exchange -> {
            assertEquals("Bearer ghs_installation_token",
                exchange.getRequestHeaders().getFirst("Authorization"));
            return json(200, "{\"total_count\":1,\"repositories\":[{"
                + "\"full_name\":\"acme/payments\",\"name\":\"payments\",\"private\":true,"
                + "\"default_branch\":\"main\",\"pushed_at\":\"2026-01-02T03:04:05Z\","
                + "\"html_url\":\"https://example.invalid/acme/payments\",\"language\":\"Java\"}]}");
        });

        List<DiscoveredResource> resources = connector.discover(appContext);

        assertEquals(1, resources.size());
        DiscoveredResource repository = resources.get(0);
        assertEquals(DiscoveredResourceType.SOURCE_REPOSITORY, repository.resourceType());
        assertEquals("acme/payments", repository.externalId());
        assertEquals("main", repository.metadata().get("defaultBranch"));
        assertEquals("true", repository.metadata().get("private"));
    }

    @Test
    void inspectSourceReadsBranchCommitTagsAndFilePaths() {
        stub("/repos/acme/payments", exchange -> json(200,
            "{\"default_branch\":\"main\",\"pushed_at\":\"2026-01-02T03:04:05Z\"}"));
        stub("/repos/acme/payments/commits", exchange -> json(200,
            "[{\"sha\":\"abc123def\",\"commit\":{\"message\":\"add billing address\","
                + "\"committer\":{\"date\":\"2026-01-02T03:04:05Z\"}}}]"));
        stub("/repos/acme/payments/tags", exchange -> json(200,
            "[{\"name\":\"v42\"},{\"name\":\"v41\"}]"));
        stub("/repos/acme/payments/git/trees", exchange -> json(200,
            "{\"tree\":[{\"path\":\"Dockerfile\"},"
                + "{\"path\":\"src/main/resources/db/migration/V41__add_billing.sql\"},"
                + "{\"path\":\"src/main/resources/db/migration/V42__drop_billing.sql\"},"
                + "{\"path\":\"src/main/resources/db/changelog/db.changelog-master.xml\"},"
                + "{\"path\":\".github/workflows/deploy.yml\"},"
                + "{\"path\":\"src/main/java/App.java\"}]}"));

        RepositoryInspection inspection = connector.inspectSource(tokenContext, "acme/payments");

        assertEquals("main", inspection.defaultBranch());
        assertEquals("abc123def", inspection.headCommitSha());
        assertEquals("add billing address", inspection.headCommitMessage());
        assertEquals(List.of("v42", "v41"), inspection.tags());
        assertTrue(inspection.hasDockerfile());
        assertEquals(List.of("src/main/resources/db/migration/V41__add_billing.sql",
            "src/main/resources/db/migration/V42__drop_billing.sql"), inspection.migrationFilePaths());
        assertEquals(List.of("src/main/resources/db/changelog/db.changelog-master.xml"),
            inspection.liquibaseChangelogPaths());
        assertEquals(List.of(".github/workflows/deploy.yml"), inspection.deploymentFilePaths());
        assertTrue(inspection.recentCommitShas().contains("abc123def"));
        assertTrue(inspection.hasAnyMigrationSource());
    }

    @Test
    void publicModeDiscoversAnOwnersRepositoriesWithoutCredentials() {
        ConnectorContext publicContext = context(new CredentialMaterial.GitHubPublic(),
            Map.of("owner", "madhavkant29"));
        stub("/users/madhavkant29", exchange -> json(200,
            "{\"login\":\"madhavkant29\",\"public_repos\":12}"));
        stub("/users/madhavkant29/repos", exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            return json(200, "[{\"full_name\":\"madhavkant29/RollbackShield\","
                + "\"name\":\"RollbackShield\",\"private\":false,\"default_branch\":\"main\","
                + "\"pushed_at\":\"2026-01-02T03:04:05Z\",\"html_url\":\"https://example.invalid\","
                + "\"language\":\"Java\"}]");
        });

        ConnectionTestResult test = connector.testConnection(publicContext);
        assertTrue(test.success());
        assertEquals("GITHUB_PUBLIC", test.details().get("authMode"));

        List<DiscoveredResource> resources = connector.discover(publicContext);

        assertEquals(1, resources.size());
        assertEquals("madhavkant29/RollbackShield", resources.get(0).externalId());
        assertEquals(null, lastAuthorization.get(), "public mode must not send an Authorization header");
    }

    @Test
    void fetchMigrationFilesParsesFlywayVersionsFromFileNames() {
        stub("/repos/acme/payments/contents/src/main/resources/db/migration/V219__drop_legacy_column.sql",
            exchange -> raw("ALTER TABLE customers DROP COLUMN legacy_code;"));

        List<MigrationFile> files = connector.fetchMigrationFiles(tokenContext, "acme/payments",
            List.of("src/main/resources/db/migration/V219__drop_legacy_column.sql"));

        assertEquals(1, files.size());
        assertEquals("V219", files.get(0).version());
        assertEquals("drop legacy column", files.get(0).description());
        assertTrue(files.get(0).content().contains("DROP COLUMN"));
    }

    @Test
    void fetchFileReturnsNullForAMissingFile() {
        stub("/repos/acme/payments/contents/missing.txt", exchange -> json(404, "{\"message\":\"Not Found\"}"));

        assertEquals(null, connector.fetchFile(tokenContext, "acme/payments", "missing.txt"));
    }

    @Test
    void migrationAndDeploymentPathDetectionIsConservative() {
        assertTrue(GitHubConnector.isMigrationPath("db/migration/V1__init.sql"));
        assertTrue(GitHubConnector.isMigrationPath("src/main/resources/db/migration/V1__init.sql"));
        assertFalse(GitHubConnector.isMigrationPath("src/main/resources/data.sql"));
        assertFalse(GitHubConnector.isMigrationPath("README.md"));
        assertTrue(GitHubConnector.isDeploymentPath(".github/workflows/deploy.yml"));
        assertTrue(GitHubConnector.isDeploymentPath("deploy/k8s/deployment.yaml"));
        assertFalse(GitHubConnector.isDeploymentPath("src/main/java/App.java"));

        assertTrue(GitHubConnector.isLiquibasePath("src/main/resources/db/changelog/db.changelog-master.xml"));
        assertTrue(GitHubConnector.isLiquibasePath("liquibase/changelog/001-init.yaml"));
        assertFalse(GitHubConnector.isLiquibasePath("src/main/resources/db/migration/V1__init.sql"));
        assertFalse(GitHubConnector.isLiquibasePath("README.md"));
    }

    private ConnectorContext context(CredentialMaterial material) {
        return context(material, Map.of());
    }

    private ConnectorContext context(CredentialMaterial material, Map<String, String> extraConfig) {
        Map<String, String> configuration = new java.util.HashMap<>();
        configuration.put("apiBaseUrl", baseUrl);
        configuration.putAll(extraConfig);
        Integration integration = Integration.pending(OrganizationId.newId(), "github",
            ConnectorType.GITHUB, baseUrl,
            IntegrationCredentialReference.none(), configuration);
        return new ConnectorContext(integration, material);
    }

    private void stub(String pathPrefix, Handler handler) {
        server.createContext(pathPrefix, exchange -> {
            try {
                HttpResponse response = handler.handle(exchange);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status, response.body.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response.body.getBytes(StandardCharsets.UTF_8));
                }
            } catch (RuntimeException e) {
                byte[] body = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
    }

    private static HttpResponse json(int status, String body) {
        return new HttpResponse(status, body);
    }

    private static HttpResponse raw(String body) {
        return new HttpResponse(200, body);
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
            + "\n-----END " + label + "-----\n";
    }

    private static boolean verifyJwt(String jwt, KeyPair keyPair) {
        try {
            String[] parts = jwt.split("\\.");
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
            signature.initVerify(keyPair.getPublic());
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (Exception e) {
            return false;
        }
    }

    private interface Handler {
        HttpResponse handle(HttpExchange exchange) throws IOException;
    }

    private record HttpResponse(int status, String body) {
    }
}
