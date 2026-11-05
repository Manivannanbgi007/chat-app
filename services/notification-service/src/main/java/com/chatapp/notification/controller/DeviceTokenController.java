package com.chatapp.notification.controller;

import com.chatapp.notification.dto.request.DeviceTokenRequest;
import com.chatapp.notification.dto.response.ApiResponseDTO;
import com.chatapp.notification.security.CurrentUser;
import com.chatapp.notification.service.DeviceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications/device-tokens")
@Tag(name = "Device tokens")
@SecurityRequirement(name = "bearerAuth")
public class DeviceTokenController {

    private final DeviceTokenService service;

    public DeviceTokenController(DeviceTokenService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Register a push token for this user (a token moves to the newest user who registers it)")
    public ApiResponseDTO<Void> register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceTokenRequest request) {
        service.register(CurrentUser.id(jwt), request);
        return ApiResponseDTO.ok("Device registered", null);
    }

    @DeleteMapping
    @Operation(summary = "Remove my push token (call it on logout)")
    public ApiResponseDTO<Void> remove(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam @NotBlank @Size(max = 4096) String token) {
        service.remove(CurrentUser.id(jwt), token);
        return ApiResponseDTO.ok("Device removed", null);
    }
}
