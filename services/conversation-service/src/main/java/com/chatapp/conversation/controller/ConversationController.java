package com.chatapp.conversation.controller;

import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.dto.request.CreateConversationRequest;
import com.chatapp.conversation.dto.response.ApiResponseDTO;
import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.security.CurrentUser;
import com.chatapp.conversation.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller is always the token owner (CurrentUser.id), never an id from the request. */
@RestController
@RequestMapping("/api/conversations")
@Tag(name = "Conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    @Operation(summary = "Start a private conversation (returns the existing one if the pair already has one) "
            + "or create a group (type GROUP, name, memberIds)")
    public ResponseEntity<ApiResponseDTO<ConversationResponse>> create(@AuthenticationPrincipal Jwt jwt,
                                                                       @Valid @RequestBody CreateConversationRequest request) {
        ConversationService.CreateResult result = conversationService.create(CurrentUser.id(jwt), request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        String message = result.created() ? "Conversation created" : "Conversation already exists";
        return ResponseEntity.status(status).body(ApiResponseDTO.ok(message, result.conversation()));
    }

    @GetMapping
    @Operation(summary = "My chat list, newest activity first")
    public ApiResponseDTO<List<ConversationResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Conversations", conversationService.list(CurrentUser.id(jwt)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One conversation (404 when it does not exist or I am not a participant)")
    public ApiResponseDTO<ConversationResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Conversation", conversationService.get(CurrentUser.id(jwt), id));
    }
}
