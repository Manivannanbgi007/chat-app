package com.chatapp.status.controller;

import com.chatapp.status.dto.request.PrivacyRequest;
import com.chatapp.status.dto.response.ApiResponseDTO;
import com.chatapp.status.dto.response.PrivacyResponse;
import com.chatapp.status.security.CurrentUser;
import com.chatapp.status.service.PrivacyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who may see MY statuses. */
@RestController
@RequestMapping("/api/statuses/privacy")
@Tag(name = "Status privacy")
public class PrivacyController {

    private final PrivacyService privacyService;

    public PrivacyController(PrivacyService privacyService) {
        this.privacyService = privacyService;
    }

    @GetMapping
    @Operation(summary = "My status privacy (default: all my contacts)")
    public ApiResponseDTO<PrivacyResponse> get(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Status privacy", privacyService.get(CurrentUser.id(jwt)));
    }

    @PutMapping
    @Operation(summary = "Change my status privacy (mode and people together)")
    public ApiResponseDTO<PrivacyResponse> update(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody PrivacyRequest request) {
        return ApiResponseDTO.ok("Status privacy saved", privacyService.update(CurrentUser.id(jwt), request));
    }
}
