package com.rollbackshield.connectors.aws.adapter;

import com.rollbackshield.integrations.domain.ConnectionTestResult;
import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.connector.Connector;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * AWS family connector. The connection test is a real STS GetCallerIdentity
 * call -- the cheapest truthful proof that the configured credentials work.
 * If the integration pins an expected account id, a mismatch fails the test,
 * which is the guard against assuming the wrong customer role (role
 * confusion / confused deputy).
 */
@Component
public class AwsConnector implements Connector {

    private final AwsClients clients;

    public AwsConnector(AwsClients clients) {
        this.clients = clients;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.AWS;
    }

    /**
     * Capabilities come from the capability providers (ECS, ECR, SQS...);
     * the family connector contributes only the connection test.
     */
    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of();
    }

    @Override
    public Set<com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind>
        supportedCredentialKinds() {
        return Set.of(
            com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind
                .AWS_CONTROL_PLANE_ROLE,
            com.rollbackshield.integrations.domain.IntegrationCredentialReference.CredentialKind
                .AWS_ASSUME_ROLE);
    }

    @Override
    public boolean requiresEndpoint() {
        return true; // the endpoint is the AWS region
    }

    @Override
    public ConnectionTestResult testConnection(ConnectorContext context) {
        try {
            GetCallerIdentityResponse identity = clients.sts(context).getCallerIdentity();
            Map<String, String> details = new LinkedHashMap<>();
            details.put("account", identity.account());
            details.put("arn", identity.arn());
            details.put("userId", identity.userId());
            details.put("region", clients.region(context).id());

            String expectedAccount = context.config("expectedAccountId", null);
            if (expectedAccount != null && !expectedAccount.isBlank()
                && !expectedAccount.equals(identity.account())) {
                return ConnectionTestResult.failed(
                    "Credentials resolve to account " + identity.account()
                        + " but the integration expects " + expectedAccount, details);
            }
            return ConnectionTestResult.ok(
                "sts:GetCallerIdentity ok for account " + identity.account(), details);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed("STS GetCallerIdentity failed: " + safeMessage(e), Map.of());
        }
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
