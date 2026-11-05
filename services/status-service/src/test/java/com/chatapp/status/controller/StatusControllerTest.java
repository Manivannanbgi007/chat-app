package com.chatapp.status.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.status.dto.request.CreateStatusRequest;
import com.chatapp.status.dto.response.MediaUrlResponse;
import com.chatapp.status.dto.response.PrivacyResponse;
import com.chatapp.status.dto.response.StatusResponse;
import com.chatapp.status.enums.PrivacyMode;
import com.chatapp.status.enums.StatusType;
import com.chatapp.status.exception.GlobalExceptionHandler;
import com.chatapp.status.security.ApiSecurityErrorHandler;
import com.chatapp.status.security.SecurityConfig;
import com.chatapp.status.service.FeedService;
import com.chatapp.status.service.PrivacyService;
import com.chatapp.status.service.StatusService;
import com.chatapp.status.service.ViewerService;
import com.chatapp.status.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** JSON in and out, validation, the error format, and that the user always comes from the token. */
@WebMvcTest(value = {StatusController.class, PrivacyController.class}, properties = {
        "security.jwt.secret=" + TestJwt.SECRET,
        "security.jwt.issuer=" + TestJwt.ISSUER})
@Import({SecurityConfig.class, ApiSecurityErrorHandler.class, GlobalExceptionHandler.class})
class StatusControllerTest {

    private static final UUID ME = UUID.randomUUID();
    private static final String KEY = "key-12345678";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JsonMapper jsonMapper;
    @MockitoBean
    private StatusService statusService;
    @MockitoBean
    private FeedService feedService;
    @MockitoBean
    private ViewerService viewerService;
    @MockitoBean
    private PrivacyService privacyService;

    private ResultActions postAsMe(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .with(jwt().jwt(token -> token.subject(ME.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private static StatusResponse aStatus() {
        return new StatusResponse(UUID.randomUUID(), ME, StatusType.TEXT, "Hi", "#1D4ED8", null, null,
                Instant.now(), Instant.now().plusSeconds(86400), true);
    }

    // ---------------------------------------------------------------- create

    @Test
    void aNewStatusIs201AndARetryIs200() throws Exception {
        when(statusService.create(eq(ME), any())).thenReturn(new StatusService.CreateResult(aStatus(), true));
        postAsMe("/api/statuses", Map.of("type", "TEXT", "text", "Hi", "backgroundColor", "#1D4ED8", "idempotencyKey", KEY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("TEXT"))
                .andExpect(jsonPath("$.data.viewedByMe").value(true));

        when(statusService.create(eq(ME), any())).thenReturn(new StatusService.CreateResult(aStatus(), false));
        postAsMe("/api/statuses", Map.of("type", "TEXT", "text", "Hi", "backgroundColor", "#1D4ED8", "idempotencyKey", KEY))
                .andExpect(status().isOk());

        verify(statusService, org.mockito.Mockito.times(2)).create(ME,
                new CreateStatusRequest(StatusType.TEXT, "Hi", "#1D4ED8", null, null, KEY));
    }

    @Test
    void theRequestNeedsATypeAndAProperIdempotencyKey() throws Exception {
        postAsMe("/api/statuses", Map.of("text", "Hi", "idempotencyKey", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.type").exists());
        postAsMe("/api/statuses", Map.of("type", "TEXT", "text", "Hi", "idempotencyKey", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.idempotencyKey").exists());
        postAsMe("/api/statuses", Map.of("type", "TEXT", "text", "Hi", "idempotencyKey", "bad key with spaces"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(statusService);
    }

    @Test
    void textAndCaptionAreLimitedTo700Characters() throws Exception {
        postAsMe("/api/statuses", Map.of("type", "TEXT", "text", "x".repeat(701), "backgroundColor", "#1D4ED8",
                "idempotencyKey", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.text").exists());
        postAsMe("/api/statuses", Map.of("type", "IMAGE", "mediaId", UUID.randomUUID(), "caption", "x".repeat(701),
                "idempotencyKey", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.caption").exists());
        verifyNoInteractions(statusService);
    }

    @Test
    void anUnknownTypeIsABadRequest() throws Exception {
        postAsMe("/api/statuses", Map.of("type", "AUDIO", "idempotencyKey", KEY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(statusService);
    }

    @Test
    void everythingNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/statuses/feed")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get("/api/statuses/mine")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/statuses").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/statuses/privacy")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- media-url

    @Test
    void mediaUrlTakesOriginalByDefaultAndRefusesOtherVariants() throws Exception {
        UUID id = UUID.randomUUID();
        when(statusService.mediaUrl(ME, id, "original")).thenReturn(new MediaUrlResponse("http://x", Instant.now()));
        mockMvc.perform(get("/api/statuses/" + id + "/media-url").with(jwt().jwt(t -> t.subject(ME.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("http://x"));

        mockMvc.perform(get("/api/statuses/" + id + "/media-url?variant=huge").with(jwt().jwt(t -> t.subject(ME.toString()))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/statuses/not-a-uuid/media-url").with(jwt().jwt(t -> t.subject(ME.toString()))))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- privacy

    @Test
    void privacyNeedsAModeAndAtMost500People() throws Exception {
        mockMvc.perform(put("/api/statuses/privacy").with(jwt().jwt(t -> t.subject(ME.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.mode").exists());

        List<UUID> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add(UUID.randomUUID());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("mode", "ONLY_SHARE_WITH");
        body.put("userIds", tooMany);
        mockMvc.perform(put("/api/statuses/privacy").with(jwt().jwt(t -> t.subject(ME.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        Map<String, Object> withNull = new HashMap<>();
        withNull.put("mode", "ONLY_SHARE_WITH");
        withNull.put("userIds", Collections.singletonList(null));
        mockMvc.perform(put("/api/statuses/privacy").with(jwt().jwt(t -> t.subject(ME.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(withNull)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(privacyService);
    }

    @Test
    void privacyIsReadAndSavedForTheTokenOwner() throws Exception {
        when(privacyService.get(ME)).thenReturn(new PrivacyResponse(PrivacyMode.CONTACTS, List.of()));
        mockMvc.perform(get("/api/statuses/privacy").with(jwt().jwt(t -> t.subject(ME.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("CONTACTS"))
                .andExpect(jsonPath("$.data.userIds").isEmpty());
    }
}
