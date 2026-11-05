package com.chatapp.user.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * A second security chain just for /internal/** (calls from other services).
 * - It runs first (order 1) and only looks at /internal/** URLs.
 * - It accepts ONLY the shared X-Internal-Token. A user JWT does not work here.
 * - The normal chain (SecurityConfig) handles everything else and never accepts the internal token.
 */
@Configuration
public class InternalSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain internalSecurityFilterChain(HttpSecurity http,
                                                           ApiSecurityErrorHandler errorHandler,
                                                           @Value("${chatapp.internal.token}") String internalToken)
            throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalTokenFilter(internalToken), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(InternalTokenFilter.AUTHORITY))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler));
        return http.build();
    }
}
