package com.chatapp.notification.controller;

import java.util.UUID;

import com.chatapp.notification.dto.response.ApiResponseDTO;
import com.chatapp.notification.dto.response.NotificationResponse;
import com.chatapp.notification.dto.response.PageResponse;
import com.chatapp.notification.dto.response.UnreadCountResponse;
import com.chatapp.notification.dto.response.UpdatedCountResponse;
import com.chatapp.notification.security.CurrentUser;
import com.chatapp.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** My notifications. The user is always the token's subject; ids of other people's notifications give 404. */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "My notifications, newest activity first")
    public ApiResponseDTO<PageResponse<NotificationResponse>> list(@AuthenticationPrincipal Jwt jwt,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponseDTO.ok("Notifications", service.list(CurrentUser.id(jwt), page, size));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many notifications I have not read")
    public ApiResponseDTO<UnreadCountResponse> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Unread count", service.unreadCount(CurrentUser.id(jwt)));
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "Mark one notification as read")
    public ApiResponseDTO<NotificationResponse> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Notification read", service.markRead(CurrentUser.id(jwt), id));
    }

    @PutMapping("/read-all")
    @Operation(summary = "Mark all my notifications as read")
    public ApiResponseDTO<UpdatedCountResponse> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Notifications read", service.markAllRead(CurrentUser.id(jwt)));
    }
}
