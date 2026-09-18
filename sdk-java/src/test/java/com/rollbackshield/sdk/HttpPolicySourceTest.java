package com.rollbackshield.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTP against a local {@link HttpServer}: proves the SDK actually
 * sends the service credential the control plane now requires, and that it
 * sends nothing when none is configured (rather than inventing one).
 */
class HttpPolicySourceTest {

    private static final String POLICY_JSON = """
        {"contractId":"contract-1","contractVersion":1,"policyVersion":1,
         "candidateEpochRequiredForAsyncWork":true,"rules":[]}""";

    private HttpServer server;
    private final AtomicReference<String> receivedCredential = new AtomicReference<>();
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/contracts/contract-1/policy", exchange -> {
            receivedCredential.set(exchange.getRequestHeaders()
                .getFirst(HttpPolicySource.SERVICE_CREDENTIAL_HEADER));
            byte[] body = POLICY_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsTheConfiguredServiceCredential() throws Exception {
        var source = new HttpPolicySource(baseUrl, "cred-123");

        PolicySnapshot snapshot = source.fetch("contract-1");

        assertThat(receivedCredential.get()).isEqualTo("cred-123");
        assertThat(snapshot.contractId()).isEqualTo("contract-1");
        assertThat(snapshot.candidateEpochRequiredForAsyncWork()).isTrue();
    }

    @Test
    void sendsNoCredentialHeaderWhenNoneIsConfigured() throws Exception {
        var source = new HttpPolicySource(baseUrl);

        source.fetch("contract-1");

        assertThat(receivedCredential.get()).isNull();
    }
}
