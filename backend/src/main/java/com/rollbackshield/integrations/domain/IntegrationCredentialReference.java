package com.rollbackshield.integrations.domain;

import java.util.Objects;

/**
 * Reference to credential material -- never the material itself. The
 * control plane persists this; the actual secret lives in the backend
 * process environment or Secrets Manager and is resolved on demand by
 * {@link CredentialResolver}.
 */
public record IntegrationCredentialReference(
    CredentialKind kind,
    String secretReference,
    String roleArn,
    String externalId
) {

    public IntegrationCredentialReference {
        Objects.requireNonNull(kind, "kind");
    }

    public enum CredentialKind {
        /** No credential needed (e.g. Flyway analysis against a local directory). */
        NONE,
        /** Use the control plane's own IAM role. Hackathon mode; not the customer path. */
        AWS_CONTROL_PLANE_ROLE,
        /** Production customer path: STS AssumeRole with an external ID. */
        AWS_ASSUME_ROLE,
        /** GitHub App installation credentials. */
        GITHUB_APP,
        /** Short-lived token held in a secret store. Local/dev mode only. */
        GITHUB_TOKEN,
        /** Unauthenticated access to public repositories only (read-only, demo/dev). */
        GITHUB_PUBLIC,
        /** Kubeconfig stored in a secret store. */
        KUBERNETES_KUBECONFIG,
        /** Database password stored in a secret store. */
        POSTGRES_PASSWORD
    }

    public static IntegrationCredentialReference none() {
        return new IntegrationCredentialReference(CredentialKind.NONE, null, null, null);
    }

    public static IntegrationCredentialReference awsControlPlaneRole() {
        return new IntegrationCredentialReference(CredentialKind.AWS_CONTROL_PLANE_ROLE, null, null, null);
    }

    public static IntegrationCredentialReference awsAssumeRole(String roleArn, String externalId) {
        return new IntegrationCredentialReference(CredentialKind.AWS_ASSUME_ROLE, null, roleArn, externalId);
    }

    public static IntegrationCredentialReference githubApp(String appId, String installationId,
                                                           String privateKeySecretReference) {
        return new IntegrationCredentialReference(CredentialKind.GITHUB_APP, privateKeySecretReference,
            appId, installationId);
    }

    public static IntegrationCredentialReference githubToken(String secretReference) {
        return new IntegrationCredentialReference(CredentialKind.GITHUB_TOKEN, secretReference, null, null);
    }

    public static IntegrationCredentialReference secret(CredentialKind kind, String secretReference) {
        return new IntegrationCredentialReference(kind, secretReference, null, null);
    }
}
