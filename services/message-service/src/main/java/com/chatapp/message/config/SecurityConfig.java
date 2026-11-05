package com.chatapp.message.config;

import java.nio.charset.StandardCharsets;

import com.chatapp.message.dto.response.ApiResponseDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which URLs are public and which need a valid access token (JWT).
 * The WebSocket handshake (/ws) is open at the HTTP level because browsers cannot send an
 * Authorization header with it: the token is checked in the STOMP CONNECT frame instead
 * (see StompAuthChannelInterceptor).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)           // tokens are sent in a header, not cookies
                .cors(AbstractHttpConfigurer::disable)           // the gateway handles CORS
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint(jsonMapper))
                        .accessDeniedHandler(accessDeniedHandler(jsonMapper)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint(jsonMapper))
                        .accessDeniedHandler(accessDeniedHandler(jsonMapper)));
        return http.build();
    }

    /** Reads the "roles" claim of the token and turns it into ROLE_USER / ROLE_ADMIN. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private AuthenticationEntryPoint entryPoint(JsonMapper jsonMapper) {
        return (request, response, ex) ->
                write(response, jsonMapper, HttpStatus.UNAUTHORIZED, "Authentication is required or the token is invalid");
    }

    private AccessDeniedHandler accessDeniedHandler(JsonMapper jsonMapper) {
        return (request, response, ex) ->
                write(response, jsonMapper, HttpStatus.FORBIDDEN, "You are not allowed to do this");
    }

    private void write(HttpServletResponse response, JsonMapper jsonMapper, HttpStatus status, String message)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonMapper.writeValueAsString(ApiResponseDTO.error(message, null)));
    }
}
