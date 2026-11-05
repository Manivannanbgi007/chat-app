package com.chatapp.media.controller;

import java.util.UUID;

import com.chatapp.media.dto.request.AttachRequest;
import com.chatapp.media.dto.request.DownloadLinkRequest;
import com.chatapp.media.dto.request.ReleaseRequest;
import com.chatapp.media.dto.response.ApiResponseDTO;
import com.chatapp.media.dto.response.LinkResponse;
import com.chatapp.media.dto.response.MediaResponse;
import com.chatapp.media.service.MediaService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Only other services call this: message-service, and status-service since Phase 8 (header X-Internal-Token, see SecurityConfig).
 * The gateway never routes /internal/**, so browsers cannot reach it.
 */
@RestController
@RequestMapping("/internal/media")
@Hidden
public class InternalMediaController {

    private final MediaService mediaService;

    public InternalMediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/{id}/attach")
    public ApiResponseDTO<MediaResponse> attach(@PathVariable UUID id, @Valid @RequestBody AttachRequest request) {
        return ApiResponseDTO.ok("Attached", mediaService.attach(id, request));
    }

    /** status-service: the status that used this file is gone. Only works with the exact attach reference. */
    @PostMapping("/{id}/release")
    public ApiResponseDTO<Void> release(@PathVariable UUID id, @Valid @RequestBody ReleaseRequest request) {
        mediaService.release(id, request);
        return ApiResponseDTO.ok("Released", null);
    }

    @PostMapping("/{id}/download-link")
    public ApiResponseDTO<LinkResponse> downloadLink(@PathVariable UUID id, @Valid @RequestBody DownloadLinkRequest request) {
        return ApiResponseDTO.ok("Link", mediaService.downloadLink(id, request));
    }
}
