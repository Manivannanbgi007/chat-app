package com.chatapp.auth.controller;

import java.util.List;
import java.util.UUID;

import com.chatapp.auth.dto.response.ApiResponseDTO;
import com.chatapp.auth.dto.response.SessionResponse;
import com.chatapp.auth.service.SessionService;
import com.chatapp.auth.util.ClientInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Active devices": needs a valid access token. The user id always comes from the token. */
@RestController
@RequestMapping("/api/auth/sessions")
@Tag(name = "Sessions")
@SecurityRequirement(name = "bearerAuth")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    @Operation(summary = "List my active devices")
    public ApiResponseDTO<List<SessionResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID currentSessionId = UUID.fromString(jwt.getClaimAsString("sid"));
        return ApiResponseDTO.ok("Active sessions", sessionService.listActive(userId, currentSessionId));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "End one of my device sessions")
    public ApiResponseDTO<Void> end(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                                    HttpServletRequest http) {
        UUID userId = UUID.fromString(jwt.getSubject());
        sessionService.endOwnSession(userId, sessionId, ClientInfo.from(http).ipAddress());
        return ApiResponseDTO.ok("Session ended", null);
    }
}
