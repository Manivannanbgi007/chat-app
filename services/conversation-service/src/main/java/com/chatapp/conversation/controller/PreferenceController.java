package com.chatapp.conversation.controller;

import java.util.UUID;

import com.chatapp.conversation.dto.request.MuteRequest;
import com.chatapp.conversation.dto.response.ApiResponseDTO;
import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.security.CurrentUser;
import com.chatapp.conversation.service.PreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own settings for a chat: PUT switches something on, DELETE switches it off. */
@RestController
@RequestMapping("/api/conversations/{id}")
@Tag(name = "Chat settings")
public class PreferenceController {

    private final PreferenceService preferenceService;

    public PreferenceController(PreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @PutMapping("/mute")
    @Operation(summary = "Mute for 8 hours, a week or always")
    public ApiResponseDTO<ConversationResponse> mute(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                     @Valid @RequestBody MuteRequest request) {
        return ApiResponseDTO.ok("Chat muted", preferenceService.mute(CurrentUser.id(jwt), id, request.duration()));
    }

    @DeleteMapping("/mute")
    @Operation(summary = "Unmute")
    public ApiResponseDTO<ConversationResponse> unmute(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Chat unmuted", preferenceService.unmute(CurrentUser.id(jwt), id));
    }

    @PutMapping("/archive")
    @Operation(summary = "Archive (also unpins)")
    public ApiResponseDTO<ConversationResponse> archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Chat archived", preferenceService.archive(CurrentUser.id(jwt), id));
    }

    @DeleteMapping("/archive")
    @Operation(summary = "Take out of the archive")
    public ApiResponseDTO<ConversationResponse> unarchive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Chat unarchived", preferenceService.unarchive(CurrentUser.id(jwt), id));
    }

    @PutMapping("/pin")
    @Operation(summary = "Pin to the top of the list (at most 3; also unarchives)")
    public ApiResponseDTO<ConversationResponse> pin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Chat pinned", preferenceService.pin(CurrentUser.id(jwt), id));
    }

    @DeleteMapping("/pin")
    @Operation(summary = "Unpin")
    public ApiResponseDTO<ConversationResponse> unpin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Chat unpinned", preferenceService.unpin(CurrentUser.id(jwt), id));
    }
}
