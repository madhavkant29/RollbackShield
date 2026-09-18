package com.rollbackshield.shared.security;

import com.rollbackshield.identity.AuthenticatedPrincipal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Converts a verified Cognito JWT (signature/issuer/audience/expiry already
 * checked by Spring's JwtDecoder -- §29, never hand-rolled base64 decoding)
 * into our AuthenticatedPrincipal. organizationId comes from a custom
 * Cognito claim populated at user-pool group/attribute setup time, not from
 * anything the client can set directly.
 */
public final class CognitoPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ORG_CLAIM = "custom:organization_id";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            jwt.getSubject(),
            jwt.getClaimAsString(ORG_CLAIM),
            jwt.getClaimAsString("email")
        );
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            principal, jwt, List.of());
    }
}
