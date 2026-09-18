package com.rollbackshield.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Two mutually exclusive filter chains, selected by Spring profile. Exactly
 * one is ever active in a running process -- there is no path where Cognito
 * validation is skipped outside 'local'.
 *
 * CORS is a separate, profile-independent concern: the Next.js control room
 * runs on its own origin (localhost:3000 locally, a CloudFront/hosting origin
 * in AWS) and calls the control plane cross-origin. Origins are an explicit
 * allow-list from configuration, never '*', and credentials are disabled
 * (the UI sends no cookies; AWS auth is a Bearer token).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
        @Value("${rollbackshield.cors.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Authorization"));
        config.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    @Profile("local")
    public SecurityFilterChain localFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(new LocalDevAuthFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Profile("!local")
    public SecurityFilterChain cognitoFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        // Delegate principal construction to CognitoPrincipalConverter rather
        // than trusting default scope-authority mapping.
        var wrapped = new org.springframework.core.convert.converter.Converter<
            org.springframework.security.oauth2.jwt.Jwt,
            org.springframework.security.authentication.AbstractAuthenticationToken>() {
            private final CognitoPrincipalConverter delegate = new CognitoPrincipalConverter();
            @Override
            public org.springframework.security.authentication.AbstractAuthenticationToken convert(
                org.springframework.security.oauth2.jwt.Jwt jwt) {
                return delegate.convert(jwt);
            }
        };

        http
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(wrapped)));
        return http.build();
    }
}
