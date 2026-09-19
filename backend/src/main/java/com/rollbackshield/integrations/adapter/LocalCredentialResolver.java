package com.rollbackshield.integrations.adapter;

import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.shared.api.ValidationException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Local/dev credential resolution: secret references are environment
 * variable names. No secret is ever stored in the repository or the control
 * plane's database in this mode, and nothing is logged.
 */
@Component
@Profile("local")
public class LocalCredentialResolver implements CredentialResolver {

    @Override
    public CredentialMaterial resolve(IntegrationCredentialReference reference) {
        return switch (reference.kind()) {
            case NONE -> new CredentialMaterial.None();
            case AWS_CONTROL_PLANE_ROLE -> new CredentialMaterial.ControlPlaneRole();
            case AWS_ASSUME_ROLE -> new CredentialMaterial.AssumedRole(
                reference.roleArn(), reference.externalId());
            case GITHUB_APP -> new CredentialMaterial.GitHubAppCredentials(
                reference.roleArn(), reference.externalId(),
                requiredSecret(reference.secretReference(), "GitHub App private key"));
            case GITHUB_TOKEN -> new CredentialMaterial.GitHubToken(
                requiredSecret(reference.secretReference(), "GitHub token"));
            case GITHUB_PUBLIC -> new CredentialMaterial.GitHubPublic();
            case KUBERNETES_KUBECONFIG -> new CredentialMaterial.Kubeconfig(
                requiredSecret(reference.secretReference(), "kubeconfig"));
            case POSTGRES_PASSWORD -> new CredentialMaterial.DatabasePassword(
                requiredSecret(reference.secretReference(), "database password"));
        };
    }

    private static String requiredSecret(String secretReference, String label) {
        String value = System.getenv(secretReference);
        if (value == null || value.isBlank()) {
            throw new ValidationException("CREDENTIAL_UNAVAILABLE",
                label + " is not available: set environment variable " + secretReference
                    + " (local profile resolves secret references from the environment)",
                Map.of("secretReference", String.valueOf(secretReference)));
        }
        return value;
    }
}
