package com.chatapp.media.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.chatapp.media.dto.response.LinkResponse;
import com.chatapp.media.dto.response.MediaResponse;
import com.chatapp.media.dto.response.OwnerMediaResponse;
import com.chatapp.media.enums.MediaKind;
import com.chatapp.media.exception.BadRequestException;
import com.chatapp.media.exception.ConflictException;
import com.chatapp.media.exception.FieldErrorException;
import com.chatapp.media.exception.GlobalExceptionHandler;
import com.chatapp.media.exception.PayloadTooLargeException;
import com.chatapp.media.exception.ResourceNotFoundException;
import com.chatapp.media.exception.UnsupportedFileException;
import com.chatapp.media.security.ApiSecurityErrorHandler;
import com.chatapp.media.security.SecurityConfig;
import com.chatapp.media.service.MediaService;
import com.chatapp.media.service.MediaUploadService;
import com.chatapp.media.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Only the web layer: status codes, error codes, JSON shape and security. The services are mocked. */
@WebMvcTest(value = {MediaController.class, InternalMediaController.class}, properties = {
        "security.jwt.secret=" + TestJwt.SECRET,
        "security.jwt.issuer=" + TestJwt.ISSUER,
        "chatapp.internal.token=" + TestJwt.INTERNAL_TOKEN})
@Import({SecurityConfig.class, ApiSecurityErrorHandler.class, GlobalExceptionHandler.class})
class MediaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MediaUploadService uploadService;
    @MockitoBean
    private MediaService mediaService;

    private final UUID me = UUID.randomUUID();
    private final UUID mediaId = UUID.randomUUID();

    private String bearer() {
        return "Bearer " + TestJwt.token(me);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3});
    }

    private MediaResponse sampleMedia() {
        return new MediaResponse(mediaId, MediaKind.IMAGE, "photo.png", "image/png", 3, 10, 20, null, true,
                Instant.parse("2026-11-04T10:00:00.123456Z"));
    }

    // ----------------------------------------------------------------- upload

    @Test
    void uploadAnswers201WithTheMedia() throws Exception {
        when(uploadService.upload(eq(me), any(), eq("IMAGE"), eq(12))).thenReturn(sampleMedia());

        mockMvc.perform(multipart("/api/media/upload").file(file()).param("purpose", "IMAGE").param("durationSeconds", "12")
                        .header("Authorization", bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(mediaId.toString()))
                .andExpect(jsonPath("$.data.kind").value("IMAGE"))
                .andExpect(jsonPath("$.data.fileName").value("photo.png"))
                .andExpect(jsonPath("$.data.mimeType").value("image/png"))
                .andExpect(jsonPath("$.data.width").value(10))
                .andExpect(jsonPath("$.data.durationSeconds").doesNotExist())
                .andExpect(jsonPath("$.data.hasThumbnail").value(true))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-04T10:00:00.123456Z"))
                .andExpect(jsonPath("$.data.objectKey").doesNotExist());     // storage keys never leave the service
    }

    @Test
    void theUploaderIsTheTokenUserNotARequestField() throws Exception {
        when(uploadService.upload(eq(me), any(), any(), any())).thenReturn(sampleMedia());

        mockMvc.perform(multipart("/api/media/upload").file(file()).param("purpose", "IMAGE")
                        .param("ownerId", UUID.randomUUID().toString())
                        .header("Authorization", bearer()))
                .andExpect(status().isCreated());
    }

    @Test
    void uploadWithoutATokenIs401InStandardFormat() throws Exception {
        mockMvc.perform(multipart("/api/media/upload").file(file()).param("purpose", "IMAGE"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(uploadService);
    }

    @Test
    void uploadWithABadTokenIs401() throws Exception {
        String expired = TestJwt.token(me, TestJwt.SECRET, TestJwt.ISSUER, Duration.ofMinutes(-10));
        String forged = TestJwt.token(me, "another-secret-another-secret-another-1234", TestJwt.ISSUER, Duration.ofMinutes(5));
        for (String token : new String[]{expired, forged, "garbage"}) {
            mockMvc.perform(multipart("/api/media/upload").file(file()).param("purpose", "IMAGE")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aMissingFileOrPurposeIs400WithTheFieldName() throws Exception {
        when(uploadService.upload(any(), any(), any(), any()))
                .thenThrow(new FieldErrorException("file", "is required and must not be empty"));

        mockMvc.perform(multipart("/api/media/upload").param("purpose", "IMAGE").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.file").value("is required and must not be empty"))
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION"));
    }

    @Test
    void aFileOverItsLimitIs413() throws Exception {
        when(uploadService.upload(any(), any(), any(), any())).thenThrow(new PayloadTooLargeException("The file is larger than 10 MB"));

        mockMvc.perform(multipart("/api/media/upload").file(file()).param("purpose", "IMAGE").header("Authorization", bearer()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.data.errorCode").value("FILE_TOO_LARGE"));
    }

    @Test
    void aRequestOverTheOuterWallIsAlso413() throws Exception {
        when(uploadService.upload(any(), any(), any(), any())).thenThrow(new MaxUploadSizeExceededException(101L * 1024 * 1024));

        mockMvc.perform(multipart("/api/media/upload").file(file()).param("purpose", "VIDEO").header("Authorization", bearer()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.data.errorCode").value("FILE_TOO_LARGE"));
    }

    @Test
    void unsupportedAndMismatchingFilesAre415() throws Exception {
        when(uploadService.upload(any(), any(), eq("IMAGE"), any()))
                .thenThrow(new UnsupportedFileException("not allowed", "UNSUPPORTED_TYPE"));
        when(uploadService.upload(any(), any(), eq("DOCUMENT"), any()))
                .thenThrow(new UnsupportedFileException("does not match", "CONTENT_MISMATCH"));

        mockMvc.perform(multipart("/api/media/upload").file(file()).param("purpose", "IMAGE").header("Authorization", bearer()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.data.errorCode").value("UNSUPPORTED_TYPE"));
        mockMvc.perform(multipart("/api/media/upload").file(file()).param("purpose", "DOCUMENT").header("Authorization", bearer()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.data.errorCode").value("CONTENT_MISMATCH"));
    }

    @Test
    void anInvalidImageIs400() throws Exception {
        when(uploadService.upload(any(), any(), any(), any())).thenThrow(new BadRequestException("bad image", "INVALID_IMAGE"));

        mockMvc.perform(multipart("/api/media/upload").file(file()).param("purpose", "IMAGE").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("INVALID_IMAGE"));
    }

    @Test
    void uploadWithJsonInsteadOfMultipartIs415() throws Exception {
        mockMvc.perform(post("/api/media/upload").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", bearer()))
                .andExpect(status().isUnsupportedMediaType());
    }

    // -------------------------------------------------------- get and delete

    @Test
    void ownerGetsMediaAndLinks() throws Exception {
        Instant expires = Instant.parse("2026-11-04T10:05:00Z");
        when(mediaService.getOwn(me, mediaId)).thenReturn(
                new OwnerMediaResponse(sampleMedia(), "http://localhost:9010/chat-media/a", "http://localhost:9010/chat-media/b", expires));

        mockMvc.perform(get("/api/media/" + mediaId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.media.id").value(mediaId.toString()))
                .andExpect(jsonPath("$.data.url").value("http://localhost:9010/chat-media/a"))
                .andExpect(jsonPath("$.data.thumbnailUrl").value("http://localhost:9010/chat-media/b"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-04T10:05:00Z"));
    }

    @Test
    void someoneElsesOrUnknownMediaIs404() throws Exception {
        when(mediaService.getOwn(any(), any())).thenThrow(new ResourceNotFoundException("Media not found", "MEDIA_NOT_FOUND"));

        mockMvc.perform(get("/api/media/" + mediaId).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("MEDIA_NOT_FOUND"));
    }

    @Test
    void aMalformedIdIs400() throws Exception {
        mockMvc.perform(get("/api/media/not-a-uuid").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAndDeleteNeedAToken() throws Exception {
        mockMvc.perform(get("/api/media/" + mediaId)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/media/" + mediaId)).andExpect(status().isUnauthorized());
    }

    @Test
    void deleteAnswers200AndAnAttachedFileIs409() throws Exception {
        mockMvc.perform(delete("/api/media/" + mediaId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        UUID attached = UUID.randomUUID();
        doThrow(new ConflictException("in use", "MEDIA_IN_USE")).when(mediaService).delete(me, attached);
        mockMvc.perform(delete("/api/media/" + attached).header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("MEDIA_IN_USE"));
    }

    // --------------------------------------------------------------- internal

    private static final String ATTACH_BODY = "{\"ownerId\":\"%s\",\"kind\":\"IMAGE\",\"reference\":\"user:key-12345678\"}";

    @Test
    void internalAttachNeedsTheInternalToken() throws Exception {
        String body = ATTACH_BODY.formatted(me);
        when(mediaService.attach(eq(mediaId), any())).thenReturn(sampleMedia());

        mockMvc.perform(post("/internal/media/" + mediaId + "/attach").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/media/" + mediaId + "/attach").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/media/" + mediaId + "/attach").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Internal-Token", TestJwt.INTERNAL_TOKEN + "x"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/media/" + mediaId + "/attach").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Internal-Token", TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(mediaId.toString()));
    }

    @Test
    void aUserTokenDoesNotOpenInternalEndpointsAndTheInternalTokenDoesNotOpenUserEndpoints() throws Exception {
        mockMvc.perform(post("/internal/media/" + mediaId + "/attach").contentType(MediaType.APPLICATION_JSON)
                        .content(ATTACH_BODY.formatted(me)).header("Authorization", bearer()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/media/" + mediaId).header("X-Internal-Token", TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/media/upload").file(file()).param("purpose", "IMAGE")
                        .header("X-Internal-Token", TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalAttachChecksTheBody() throws Exception {
        mockMvc.perform(post("/internal/media/" + mediaId + "/attach").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"IMAGE\",\"reference\":\"x\"}").header("X-Internal-Token", TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.ownerId").exists());
        mockMvc.perform(post("/internal/media/" + mediaId + "/attach").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + me + "\",\"kind\":\"BANANA\",\"reference\":\"x\"}")
                        .header("X-Internal-Token", TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void internalErrorsKeepTheirCodes() throws Exception {
        when(mediaService.attach(any(), any()))
                .thenThrow(new ConflictException("used", "ATTACHMENT_ALREADY_USED"))
                .thenThrow(new BadRequestException("kind", "ATTACHMENT_TYPE_MISMATCH"))
                .thenThrow(new ResourceNotFoundException("nope", "ATTACHMENT_NOT_FOUND"));
        String body = ATTACH_BODY.formatted(me);

        mockMvc.perform(post("/internal/media/" + mediaId + "/attach").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Internal-Token", TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.errorCode").value("ATTACHMENT_ALREADY_USED"));
        mockMvc.perform(post("/internal/media/" + mediaId + "/attach").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Internal-Token", TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errorCode").value("ATTACHMENT_TYPE_MISMATCH"));
        mockMvc.perform(post("/internal/media/" + mediaId + "/attach").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Internal-Token", TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data.errorCode").value("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void internalDownloadLinkReturnsUrlAndExpiry() throws Exception {
        Instant expires = Instant.parse("2026-11-04T10:05:00Z");
        when(mediaService.downloadLink(eq(mediaId), any())).thenReturn(new LinkResponse("http://localhost:9010/x", expires));
        String body = "{\"requesterId\":\"" + me + "\",\"variant\":\"thumbnail\"}";

        mockMvc.perform(post("/internal/media/" + mediaId + "/download-link").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/media/" + mediaId + "/download-link").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Internal-Token", TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("http://localhost:9010/x"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-04T10:05:00Z"));
    }

    @Test
    void internalDownloadLinkOnlyKnowsOriginalAndThumbnail() throws Exception {
        for (String variant : new String[]{"big", "ORIGINAL", ""}) {
            mockMvc.perform(post("/internal/media/" + mediaId + "/download-link").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requesterId\":\"" + me + "\",\"variant\":\"" + variant + "\"}")
                            .header("X-Internal-Token", TestJwt.INTERNAL_TOKEN))
                    .andExpect(status().isBadRequest());
        }
    }
}
