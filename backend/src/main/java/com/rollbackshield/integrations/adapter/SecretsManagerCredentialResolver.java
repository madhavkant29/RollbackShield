package com.rollbackshield.integrations.adapter;

import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.shared.api.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.util.Map;

/**
 * AWS-profile credential resolution: secret references name Secrets Manager
 * secrets, resolved with the control plane's own task role. Secret values
 * are held only for the duration of a connector call and are never
 * persisted, logged, or returned by any API.
 */
@Component
@Profile("!local")
public class SecretsManagerCredentialResolver implements CredentialResolver {

    private final SecretsManagerClient secretsManager;

    public SecretsManagerCredentialResolver(
        @Value("${rollbackshield.aws.region:${AWS_REGION:us-east-1}}") String region) {
        this.secretsManager = SecretsManagerClient.builder().region(Region.of(region)).build();
    }

    @Override
    public CredentialMaterial resolve(IntegrationCredentialReference reference) {
        return switch (reference.kind()) {
            case NONE -> new CredentialMaterial.None();
            case AWS_CONTROL_PLANE_ROLE -> new CredentialMaterial.ControlPlaneRole();
            case AWS_ASSUME_ROLE -> new CredentialMaterial.AssumedRole(
                reference.roleArn(), reference.externalId());
            case GITHUB_APP -> new CredentialMaterial.GitHubAppCredentials(
                reference.roleArn(), reference.externalId(), secret(reference));
            case GITHUB_TOKEN -> new CredentialMaterial.GitHubToken(secret(reference));
            case GITHUB_PUBLIC -> new CredentialMaterial.GitHubPublic();
            case KUBERNETES_KUBECONFIG -> new CredentialMaterial.Kubeconfig(secret(reference));
            case POSTGRES_PASSWORD -> new CredentialMaterial.DatabasePassword(secret(reference));
        };
    }

    private String secret(IntegrationCredentialReference reference) {
        String secretId = reference.secretReference();
        if (secretId == null || secretId.isBlank()) {
            throw new ValidationException("CREDENTIAL_UNAVAILABLE",
                "No secret reference configured for " + reference.kind(),
                Map.of("credentialKind", reference.kind().name()));
        }
        try {
            return secretsManager.getSecretValue(GetSecretValueRequest.builder()
                .secretId(secretId).build()).secretString();
        } catch (ResourceNotFoundException e) {
            throw new ValidationException("CREDENTIAL_UNAVAILABLE",
                "Secret " + secretId + " does not exist or the task role cannot read it",
                Map.of("credentialKind", reference.kind().name()));
        }
    }
}
