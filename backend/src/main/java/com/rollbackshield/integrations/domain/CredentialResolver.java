package com.rollbackshield.integrations.domain;

/**
 * Resolves stored credential references into usable material at call time.
 * Implementations live in adapters (environment variables locally, Secrets
 * Manager in AWS); the application layer only sees this port.
 */
public interface CredentialResolver {

    CredentialMaterial resolve(IntegrationCredentialReference reference);
}
