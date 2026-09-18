package com.rollbackshield.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Authenticates the three endpoints that are called by trusted
 * infrastructure rather than a tenant user:
 *
 * <ul>
 *   <li>{@code GET  /api/v1/work/poll}</li>
 *   <li>{@code POST /api/v1/work/{jobId}/redeem}</li>
 *   <li>{@code GET  /api/v1/contracts/{contractId}/policy} (the SDK fetch)</li>
 * </ul>
 *
 * These previously relied on being inside a trusted network or on an
 * unguessable UUID (see SECURITY_ARCHITECTURE.md's old "known open gaps").
 * They now require the shared service credential in
 * {@code X-RollbackShield-Service-Credential}, compared in constant time.
 *
 * The credential is intentionally <em>not</em> accepted on tenant
 * endpoints, and a user JWT is not accepted here -- the service filter only
 * authenticates the paths above, and those paths' authorization rule
 * requires the {@code SERVICE} authority this filter grants. A blank
 * configured credential fails closed (every service call is rejected).
 */
public final class ServiceCredentialAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-RollbackShield-Service-Credential";
    public static final String SERVICE_AUTHORITY = "SERVICE";
    public static final String ERROR_CODE = "INVALID_SERVICE_CREDENTIAL";

    private static final Logger log = LoggerFactory.getLogger(ServiceCredentialAuthFilter.class);
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final byte[] expectedCredential;

    public ServiceCredentialAuthFilter(String serviceCredential) {
        this.expectedCredential = serviceCredential == null
            ? new byte[0]
            : serviceCredential.getBytes(StandardCharsets.UTF_8);
        if (expectedCredential.length == 0) {
            log.warn("rollbackshield.service-credential is not configured; all service-credential "
                + "endpoints (/work/**, /contracts/*/policy) will reject callers");
        }
    }

    /** The matchers whose authorization rule requires {@link #SERVICE_AUTHORITY}. */
    public static RequestMatcher[] protectedRequestMatchers() {
        return new RequestMatcher[] {
            new AntPathRequestMatcher("/api/v1/work/poll", "GET"),
            new AntPathRequestMatcher("/api/v1/work/*/redeem", "POST"),
            new AntPathRequestMatcher("/api/v1/contracts/*/policy", "GET"),
        };
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return !(("GET".equals(method) && PATHS.match("/api/v1/work/poll", path))
            || ("POST".equals(method) && PATHS.match("/api/v1/work/*/redeem", path))
            || ("GET".equals(method) && PATHS.match("/api/v1/contracts/*/policy", path)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);

        if (expectedCredential.length > 0 && provided != null
            && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedCredential)) {
            var authentication = new UsernamePasswordAuthenticationToken(
                "service-credential", null, List.of(new SimpleGrantedAuthority(SERVICE_AUTHORITY)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
            return;
        }

        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + ERROR_CODE + "\",\"message\":\"Missing or invalid "
            + "service credential\",\"timestamp\":\"" + Instant.now() + "\",\"requestId\":\""
            + UUID.randomUUID() + "\",\"details\":{}}");
    }
}
