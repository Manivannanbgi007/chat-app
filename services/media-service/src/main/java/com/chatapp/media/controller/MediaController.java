package com.chatapp.media.controller;

import java.util.UUID;

import com.chatapp.media.dto.response.ApiResponseDTO;
import com.chatapp.media.dto.response.MediaResponse;
import com.chatapp.media.dto.response.OwnerMediaResponse;
import com.chatapp.media.security.CurrentUser;
import com.chatapp.media.service.MediaService;
import com.chatapp.media.service.MediaUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The public media API (through the gateway). The user is always the one in the token. */
@RestController
@RequestMapping("/api/media")
@Tag(name = "Media")
public class MediaController {

    private final MediaUploadService uploadService;
    private final MediaService mediaService;

    public MediaController(MediaUploadService uploadService, MediaService mediaService) {
        this.uploadService = uploadService;
        this.mediaService = mediaService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a file (purpose IMAGE, VIDEO, DOCUMENT, AUDIO or VOICE)")
    public ResponseEntity<ApiResponseDTO<MediaResponse>> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "purpose", required = false) String purpose,
            @RequestParam(value = "durationSeconds", required = false) Integer durationSeconds) {
        MediaResponse media = uploadService.upload(CurrentUser.id(jwt), file, purpose, durationSeconds);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseDTO.ok("File uploaded", media));
    }

    @GetMapping("/{mediaId}")
    @Operation(summary = "My own upload: details and signed links (404 for anybody else)")
    public ApiResponseDTO<OwnerMediaResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId) {
        return ApiResponseDTO.ok("Media", mediaService.getOwn(CurrentUser.id(jwt), mediaId));
    }

    @DeleteMapping("/{mediaId}")
    @Operation(summary = "Delete my upload (only while no message uses it)")
    public ApiResponseDTO<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId) {
        mediaService.delete(CurrentUser.id(jwt), mediaId);
        return ApiResponseDTO.ok("File deleted", null);
    }
}
