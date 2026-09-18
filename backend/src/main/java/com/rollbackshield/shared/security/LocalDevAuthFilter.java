package com.rollbackshield.shared.security;

import com.rollbackshield.identity.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Active only under the 'local' Spring profile (wired in SecurityConfig).
 * Stands in for Cognito during local development: every request is treated
 * as a fixed dev principal so the API boundary can be exercised with
 * `mvn spring-boot:run` and no AWS account. This filter is never wired when
 * any profile other than 'local' is active -- see SecurityConfig's two
 * @Profile-gated SecurityFilterChain beans.
 */
public final class LocalDevAuthFilter extends OncePerRequestFilter {

    /** Matches the organization seeded at startup by LocalDevDataSeeder. */
    public static final String LOCAL_DEV_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

    private static final AuthenticatedPrincipal DEV_PRINCIPAL =
        new AuthenticatedPrincipal("local-dev-user", LOCAL_DEV_ORGANIZATION_ID, "dev@localhost");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        var auth = new UsernamePasswordAuthenticationToken(DEV_PRINCIPAL, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }
}
