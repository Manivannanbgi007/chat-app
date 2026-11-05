package com.chatapp.message.controller;

import java.util.List;
import java.util.UUID;

import com.chatapp.message.dto.request.DeliveredRequest;
import com.chatapp.message.dto.request.ReadRequest;
import com.chatapp.message.dto.request.SendMessageRequest;
import com.chatapp.message.dto.response.ApiResponseDTO;
import com.chatapp.message.dto.response.ConversationSummaryDto;
import com.chatapp.message.dto.response.MessageDto;
import com.chatapp.message.dto.response.PageDto;
import com.chatapp.message.dto.response.ReceiptDetailDto;
import com.chatapp.message.dto.response.SendResult;
import com.chatapp.message.dto.response.UpdatedCountDto;
import com.chatapp.message.dto.response.DownloadLinkDto;
import com.chatapp.message.service.AttachmentService;
import com.chatapp.message.service.MessageService;
import com.chatapp.message.service.ReceiptService;
import com.chatapp.message.util.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API for messages. The user id and device id always come from the access token, never from the request. */
@RestController
@Tag(name = "Messages")
@SecurityRequirement(name = "bearerAuth")
public class MessageController {

    private final MessageService messageService;
    private final ReceiptService receiptService;
    private final AttachmentService attachmentService;

    public MessageController(MessageService messageService, ReceiptService receiptService,
                             AttachmentService attachmentService) {
        this.messageService = messageService;
        this.receiptService = receiptService;
        this.attachmentService = attachmentService;
    }

    @GetMapping("/api/messages/{messageId}/attachment/url")
    @Operation(summary = "A signed link (5 minutes) to the attachment of a message I may see; variant = original | thumbnail")
    public ApiResponseDTO<DownloadLinkDto> attachmentUrl(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID messageId,
                                                         @RequestParam(required = false) String variant) {
        return ApiResponseDTO.ok("Attachment link", attachmentService.link(CurrentUser.id(jwt), messageId, variant));
    }

    @PostMapping("/api/messages")
    @Operation(summary = "Send a text or media message (safe to retry with the same idempotencyKey)")
    public ResponseEntity<ApiResponseDTO<MessageDto>> send(@AuthenticationPrincipal Jwt jwt,
                                                           @Valid @RequestBody SendMessageRequest request) {
        SendResult result = messageService.send(CurrentUser.id(jwt), request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponseDTO.ok(
                result.created() ? "Message sent" : "Message was already sent", result.message()));
    }

    @GetMapping("/api/conversations/{conversationId}/messages")
    @Operation(summary = "Message history: newest page, or 'before' a cursor, or 'after' a cursor")
    public ApiResponseDTO<PageDto<MessageDto>> history(@AuthenticationPrincipal Jwt jwt,
                                                       @PathVariable UUID conversationId,
                                                       @RequestParam(required = false) Integer limit,
                                                       @RequestParam(required = false) String before,
                                                       @RequestParam(required = false) String after) {
        return ApiResponseDTO.ok("Messages", messageService.history(CurrentUser.id(jwt), conversationId, limit, before, after));
    }

    @PostMapping("/api/messages/delivered")
    @Operation(summary = "This device received these messages")
    public ApiResponseDTO<UpdatedCountDto> delivered(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody DeliveredRequest request) {
        int updated = receiptService.markDelivered(CurrentUser.id(jwt), CurrentUser.deviceId(jwt), request.messageIds());
        return ApiResponseDTO.ok("Delivery recorded", new UpdatedCountDto(updated));
    }

    @PostMapping("/api/conversations/{conversationId}/read")
    @Operation(summary = "Mark messages up to a message as read")
    public ApiResponseDTO<UpdatedCountDto> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId,
                                                @Valid @RequestBody ReadRequest request) {
        int updated = receiptService.markRead(CurrentUser.id(jwt), CurrentUser.deviceId(jwt),
                conversationId, request.upToMessageId());
        return ApiResponseDTO.ok("Read recorded", new UpdatedCountDto(updated));
    }

    @GetMapping("/api/messages/{messageId}/receipts")
    @Operation(summary = "Message info: who received and read one of MY messages (404 for everyone else)")
    public ApiResponseDTO<List<ReceiptDetailDto>> receipts(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID messageId) {
        return ApiResponseDTO.ok("Receipts", receiptService.receiptDetails(CurrentUser.id(jwt), messageId));
    }

    @GetMapping("/api/messages/summaries")
    @Operation(summary = "Last message and unread count per conversation (for the chat list)")
    public ApiResponseDTO<List<ConversationSummaryDto>> summaries(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Summaries", messageService.summaries(CurrentUser.id(jwt)));
    }

    @GetMapping("/api/messages/sync")
    @Operation(summary = "Everything new in all my conversations since a cursor (use after reconnecting)")
    public ApiResponseDTO<PageDto<MessageDto>> sync(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam(required = false) String after,
                                                    @RequestParam(required = false) Integer limit) {
        return ApiResponseDTO.ok("Sync", messageService.sync(CurrentUser.id(jwt), after, limit));
    }
}
