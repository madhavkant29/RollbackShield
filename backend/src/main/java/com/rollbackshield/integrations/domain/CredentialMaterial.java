package com.rollbackshield.integrations.domain;

/**
 * Resolved credential material handed to a connector for the duration of one
 * call. Typed rather than a string map so a connector cannot accidentally
 * read a credential kind it was not meant to handle.
 */
public sealed interface CredentialMaterial
    permits CredentialMaterial.None,
            CredentialMaterial.ControlPlaneRole,
            CredentialMaterial.AssumedRole,
            CredentialMaterial.GitHubAppCredentials,
            CredentialMaterial.GitHubToken,
            CredentialMaterial.GitHubPublic,
            CredentialMaterial.Kubeconfig,
            CredentialMaterial.DatabasePassword {

    record None() implements CredentialMaterial {
    }

    /** Use the process's ambient AWS credentials (task role / local profile). */
    record ControlPlaneRole() implements CredentialMaterial {
    }

    record AssumedRole(String roleArn, String externalId) implements CredentialMaterial {
        public AssumedRole {
            if (roleArn == null || roleArn.isBlank()) {
                throw new IllegalArgumentException("roleArn is required for ASSUME_ROLE");
            }
        }
    }

    record GitHubAppCredentials(String appId, String installationId, String privateKeyPem)
        implements CredentialMaterial {
        public GitHubAppCredentials {
            if (appId == null || appId.isBlank()) {
                throw new IllegalArgumentException("appId is required");
            }
            if (installationId == null || installationId.isBlank()) {
                throw new IllegalArgumentException("installationId is required");
            }
            if (privateKeyPem == null || privateKeyPem.isBlank()) {
                throw new IllegalArgumentException("privateKeyPem is required");
            }
        }
    }

    record GitHubToken(String token) implements CredentialMaterial {
        public GitHubToken {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("token is required");
            }
        }
    }

    /** Unauthenticated GitHub access; only public repositories are visible. */
    record GitHubPublic() implements CredentialMaterial {
    }

    record Kubeconfig(String yaml) implements CredentialMaterial {
        public Kubeconfig {
            if (yaml == null || yaml.isBlank()) {
                throw new IllegalArgumentException("kubeconfig is required");
            }
        }
    }

    record DatabasePassword(String password) implements CredentialMaterial {
        public DatabasePassword {
            if (password == null) {
                throw new IllegalArgumentException("password must not be null");
            }
        }
    }
}
