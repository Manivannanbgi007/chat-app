package com.chatapp.conversation.controller;

import java.util.UUID;

import com.chatapp.conversation.dto.response.ApiResponseDTO;
import com.chatapp.conversation.dto.response.InternalConversationResponse;
import com.chatapp.conversation.dto.response.MutedUsersResponse;
import com.chatapp.conversation.service.ConversationService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Only other services call this (header X-Internal-Token, see SecurityConfig).
 * The gateway never routes /internal/**, so browsers cannot reach it.
 */
@RestController
@RequestMapping("/internal/conversations")
@Hidden
public class InternalConversationController {

    private final ConversationService conversationService;

    public InternalConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** Who has muted this chat right now (notification-service does not notify them). */
    @GetMapping("/{id}/muted-users")
    public ApiResponseDTO<MutedUsersResponse> mutedUsers(@PathVariable UUID id) {
        return ApiResponseDTO.ok("Muted users", new MutedUsersResponse(conversationService.getMutedUserIdsForInternalUse(id)));
    }

    @GetMapping("/{id}")
    public ApiResponseDTO<InternalConversationResponse> get(@PathVariable UUID id) {
        return ApiResponseDTO.ok("Conversation", conversationService.getForInternalUse(id));
    }
}
