package com.chatapp.presence.controller;

import java.util.UUID;

import com.chatapp.presence.dto.response.ApiResponseDTO;
import com.chatapp.presence.dto.response.PresenceResponse;
import com.chatapp.presence.security.CurrentUser;
import com.chatapp.presence.service.PresenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The user and the device always come from the access token, never from the request. */
@RestController
@RequestMapping("/api/presence")
@Tag(name = "Presence")
public class PresenceController {

    private final PresenceService presenceService;

    public PresenceController(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @PostMapping("/heartbeat")
    @Operation(summary = "This device is alive (send one every 20 seconds while the app is open)")
    public ApiResponseDTO<Void> heartbeat(@AuthenticationPrincipal Jwt jwt) {
        presenceService.heartbeat(CurrentUser.id(jwt), CurrentUser.deviceId(jwt));
        return ApiResponseDTO.ok("Heartbeat recorded", null);
    }

    @PostMapping("/offline")
    @Operation(summary = "This device is gone (logout, page closed). The user stays online if another device is alive")
    public ApiResponseDTO<Void> offline(@AuthenticationPrincipal Jwt jwt) {
        presenceService.goOffline(CurrentUser.id(jwt), CurrentUser.deviceId(jwt));
        return ApiResponseDTO.ok("Device is offline", null);
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Online status and last seen of a user, as far as their privacy settings allow me to see it")
    public ApiResponseDTO<PresenceResponse> presence(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        return ApiResponseDTO.ok("Presence", presenceService.presenceOf(CurrentUser.id(jwt), userId));
    }
}
