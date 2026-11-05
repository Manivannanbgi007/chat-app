package com.chatapp.user.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets other services call /internal/** by sending the header X-Internal-Token.
 * If the token matches, the request is marked as coming from "internal-service".
 * If not, nothing is marked and the security rules answer 401.
 *
 * This class is deliberately NOT a Spring bean: it is created inside InternalSecurityConfig and only
 * used by the /internal/** security chain (a filter bean would be registered for every URL).
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    public static final String AUTHORITY = "INTERNAL_SERVICE";
    private static final int MIN_TOKEN_LENGTH = 24;

    private final byte[] expectedToken;

    public InternalTokenFilter(String expectedToken) {
        // Fail at startup instead of running with a weak (or missing) shared token.
        if (expectedToken == null || expectedToken.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("INTERNAL_SERVICE_TOKEN must be at least " + MIN_TOKEN_LENGTH + " characters long");
        }
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null && tokenMatches(provided)) {
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    "internal-service", null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    /** Constant-time comparison: the time it takes does not reveal how many characters were right. */
    private boolean tokenMatches(String provided) {
        return MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8));
    }
}
