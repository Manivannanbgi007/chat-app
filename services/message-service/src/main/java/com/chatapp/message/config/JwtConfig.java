package com.chatapp.message.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Checks access tokens issued by auth-service (HS256, shared secret). Used for REST calls
 * and for the STOMP CONNECT frame of a WebSocket.
 */
@Configuration
public class JwtConfig {

    public static final String ISSUER = "chat-app-auth";

    @Bean
    public JwtDecoder jwtDecoder(AppProperties properties) {
        byte[] bytes = String.valueOf(properties.security().jwtSecret()).getBytes(StandardCharsets.UTF_8);
        // HS256 needs at least 256 bits. Fail at startup instead of running with a weak key.
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters long");
        }
        SecretKey key = new SecretKeySpec(bytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }
}
