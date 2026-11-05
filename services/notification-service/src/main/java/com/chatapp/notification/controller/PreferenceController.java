package com.chatapp.notification.controller;

import com.chatapp.notification.dto.request.PreferencesRequest;
import com.chatapp.notification.dto.response.ApiResponseDTO;
import com.chatapp.notification.dto.response.PreferencesResponse;
import com.chatapp.notification.security.CurrentUser;
import com.chatapp.notification.service.PreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications/preferences")
@Tag(name = "Notification preferences")
@SecurityRequirement(name = "bearerAuth")
public class PreferenceController {

    private final PreferenceService service;

    public PreferenceController(PreferenceService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "My notification switches (everything is on until I change it)")
    public ApiResponseDTO<PreferencesResponse> get(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Preferences", service.get(CurrentUser.id(jwt)));
    }

    @PutMapping
    @Operation(summary = "Replace my notification switches")
    public ApiResponseDTO<PreferencesResponse> update(@AuthenticationPrincipal Jwt jwt,
                                                      @Valid @RequestBody PreferencesRequest request) {
        return ApiResponseDTO.ok("Preferences saved", service.update(CurrentUser.id(jwt), request));
    }
}
