package com.chatapp.status.controller;

import java.util.List;
import java.util.UUID;

import com.chatapp.status.dto.request.CreateStatusRequest;
import com.chatapp.status.dto.response.ApiResponseDTO;
import com.chatapp.status.dto.response.MediaUrlResponse;
import com.chatapp.status.dto.response.MyStatusResponse;
import com.chatapp.status.dto.response.StatusGroupResponse;
import com.chatapp.status.dto.response.StatusResponse;
import com.chatapp.status.dto.response.ViewersResponse;
import com.chatapp.status.security.CurrentUser;
import com.chatapp.status.service.FeedService;
import com.chatapp.status.service.StatusService;
import com.chatapp.status.service.ViewerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Statuses (stories). The user is always the token's owner (CurrentUser.id), never an id from the request. */
@RestController
@RequestMapping("/api/statuses")
@Tag(name = "Statuses")
public class StatusController {

    private final StatusService statusService;
    private final FeedService feedService;
    private final ViewerService viewerService;

    public StatusController(StatusService statusService, FeedService feedService, ViewerService viewerService) {
        this.statusService = statusService;
        this.feedService = feedService;
        this.viewerService = viewerService;
    }

    @PostMapping
    @Operation(summary = "Post a text, photo or video status (safe to retry with the same idempotencyKey)")
    public ResponseEntity<ApiResponseDTO<StatusResponse>> create(@AuthenticationPrincipal Jwt jwt,
                                                                 @Valid @RequestBody CreateStatusRequest request) {
        StatusService.CreateResult result = statusService.create(CurrentUser.id(jwt), request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponseDTO.ok(
                result.created() ? "Status posted" : "Status was already posted", result.status()));
    }

    @GetMapping("/mine")
    @Operation(summary = "My active statuses, oldest first, with view counts")
    public ApiResponseDTO<List<MyStatusResponse>> mine(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("My statuses", statusService.mine(CurrentUser.id(jwt)));
    }

    @GetMapping("/feed")
    @Operation(summary = "Statuses of the people whose updates I may see, grouped by person")
    public ApiResponseDTO<List<StatusGroupResponse>> feed(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponseDTO.ok("Status feed", feedService.feed(CurrentUser.id(jwt)));
    }

    @PostMapping("/{id}/view")
    @Operation(summary = "I saw this status (safe to repeat)")
    public ApiResponseDTO<Void> view(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        statusService.view(CurrentUser.id(jwt), id);
        return ApiResponseDTO.ok("View recorded", null);
    }

    @GetMapping("/{id}/viewers")
    @Operation(summary = "Who saw my status (read receipts decide who is listed)")
    public ApiResponseDTO<ViewersResponse> viewers(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponseDTO.ok("Viewers", viewerService.viewers(CurrentUser.id(jwt), id));
    }

    @GetMapping("/{id}/media-url")
    @Operation(summary = "A signed 5-minute link to the photo / video of a status (or its thumbnail)")
    public ApiResponseDTO<MediaUrlResponse> mediaUrl(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "original")
            @Pattern(regexp = "^(original|thumbnail)$", message = "must be original or thumbnail") String variant) {
        return ApiResponseDTO.ok("Link", statusService.mediaUrl(CurrentUser.id(jwt), id, variant));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete my status (and its photo / video)")
    public ApiResponseDTO<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        statusService.delete(CurrentUser.id(jwt), id);
        return ApiResponseDTO.ok("Status deleted", null);
    }
}
