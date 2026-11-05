package com.chatapp.conversation.controller;

import java.util.UUID;

import com.chatapp.conversation.dto.request.AddMembersRequest;
import com.chatapp.conversation.dto.request.ChangeRoleRequest;
import com.chatapp.conversation.dto.request.UpdateGroupRequest;
import com.chatapp.conversation.dto.response.ApiResponseDTO;
import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.security.CurrentUser;
import com.chatapp.conversation.service.GroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Managing a group. Every answer is the group as the caller sees it after the change.
 * The caller is always the token owner; {userId} in a path is the member the action is about.
 * (Groups are created with POST /api/conversations, type GROUP.)
 */
@RestController
@RequestMapping("/api/conversations/{id}")
@Tag(name = "Groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PatchMapping("/group")
    @Operation(summary = "Change the name, description or settings (settings: admins only)")
    public ApiResponseDTO<ConversationResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                       @RequestBody UpdateGroupRequest request) {
        return ApiResponseDTO.ok("Group updated", groupService.updateGroup(CurrentUser.id(jwt), id, request));
    }

    @PostMapping("/members")
    @Operation(summary = "Add members (admins only)")
    public ApiResponseDTO<ConversationResponse> addMembers(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                           @Valid @RequestBody AddMembersRequest request) {
        return ApiResponseDTO.ok("Members added", groupService.addMembers(CurrentUser.id(jwt), id, request));
    }

    @DeleteMapping("/members/{userId}")
    @Operation(summary = "Remove a member (admins only, not yourself: use leave)")
    public ApiResponseDTO<ConversationResponse> removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                             @PathVariable UUID userId) {
        return ApiResponseDTO.ok("Member removed", groupService.removeMember(CurrentUser.id(jwt), id, userId));
    }

    @PutMapping("/members/{userId}/role")
    @Operation(summary = "Make a member admin (ADMIN) or dismiss as admin (MEMBER) (admins only)")
    public ApiResponseDTO<ConversationResponse> changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                           @PathVariable UUID userId,
                                                           @Valid @RequestBody ChangeRoleRequest request) {
        return ApiResponseDTO.ok("Role changed",
                groupService.changeRole(CurrentUser.id(jwt), id, userId, request.role()));
    }

    @PostMapping("/leave")
    @Operation(summary = "Leave the group (it stays in your list, read-only)")
    public ApiResponseDTO<ConversationResponse> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("You left the group", groupService.leave(CurrentUser.id(jwt), id));
    }
}
