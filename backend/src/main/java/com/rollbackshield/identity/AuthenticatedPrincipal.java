package com.rollbackshield.identity;

/**
 * The authenticated caller, resolved identically whether the request came
 * through Cognito JWT validation or the local-dev filter. Application code
 * depends only on this interface, never on Spring Security's Authentication
 * type or on JWT claim names directly (§30: tenant scope must come from
 * authenticated membership, not a client-supplied organizationId).
 */
public record AuthenticatedPrincipal(String userId, String organizationId, String email) {
}
