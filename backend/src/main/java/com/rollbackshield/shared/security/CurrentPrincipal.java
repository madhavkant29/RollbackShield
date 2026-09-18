package com.rollbackshield.shared.security;

import com.rollbackshield.identity.AuthenticatedPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the AuthenticatedPrincipal for the current request. Application
 * services call this instead of trusting any organizationId a client sends
 * in a request body or path -- §30 tenant-scoping requirement.
 */
public final class CurrentPrincipal {

    private CurrentPrincipal() {
    }

    public static AuthenticatedPrincipal get() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedPrincipal p) {
            return p;
        }
        throw new IllegalStateException(
            "No AuthenticatedPrincipal on the security context -- check SecurityConfig wiring");
    }
}
