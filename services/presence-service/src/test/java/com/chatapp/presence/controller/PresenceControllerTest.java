package com.chatapp.presence.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.chatapp.presence.dto.response.PresenceResponse;
import com.chatapp.presence.exception.DependencyUnavailableException;
import com.chatapp.presence.exception.GlobalExceptionHandler;
import com.chatapp.presence.security.ApiSecurityErrorHandler;
import com.chatapp.presence.security.InternalTokenFilter;
import com.chatapp.presence.security.SecurityConfig;
import com.chatapp.presence.service.PresenceService;
import com.chatapp.presence.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web layer only (the service is mocked): status codes, who may call what, the error format.
 * Both security chains (user JWT for /api, internal token for /internal) are loaded for real.
 */
@WebMvcTest({PresenceController.class, InternalPresenceController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, ApiSecurityErrorHandler.class, GlobalExceptionHandler.class})
class PresenceControllerTest {

    private static final String TOKEN_HEADER = InternalTokenFilter.HEADER;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private PresenceService presenceService;

    private final UUID alice = UUID.randomUUID();
    private final UUID aliceDevice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private String bearer() {
        return "Bearer " + TestJwt.token(alice, aliceDevice);
    }

    // ---- who may call ----

    @Test
    void userEndpointsNeedAToken() throws Exception {
        mockMvc.perform(post("/api/presence/heartbeat")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post("/api/presence/offline")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/presence/" + bob)).andExpect(status().isUnauthorized());
        verifyNoInteractions(presenceService);
    }

    @Test
    void aGarbageTokenIsRefused() throws Exception {
        mockMvc.perform(post("/api/presence/heartbeat").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(presenceService);
    }

    @Test
    void aTokenWithoutADeviceIsRefusedForHeartbeats() throws Exception {
        mockMvc.perform(post("/api/presence/heartbeat")
                        .header("Authorization", "Bearer " + TestJwt.tokenWithoutDevice(alice)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(presenceService);
    }

    @Test
    void aTokenWithABrokenUserIdIsRefused() throws Exception {
        mockMvc.perform(post("/api/presence/heartbeat")
                        .header("Authorization", "Bearer " + TestJwt.build("not-a-uuid", aliceDevice.toString())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(presenceService);
    }

    @Test
    void theInternalTokenDoesNotOpenTheUserApi() throws Exception {
        mockMvc.perform(post("/api/presence/heartbeat").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(presenceService);
    }

    @Test
    void aUserTokenDoesNotOpenTheInternalApi() throws Exception {
        mockMvc.perform(get("/internal/presence/online").param("userIds", bob.toString())
                        .header("Authorization", bearer()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(presenceService);
    }

    @Test
    void internalEndpointsNeedTheCorrectInternalToken() throws Exception {
        mockMvc.perform(get("/internal/presence/online").param("userIds", bob.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/presence/online").param("userIds", bob.toString())
                        .header(TOKEN_HEADER, "wrong-token-wrong-token-wrong-1234"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/presence/online").param("userIds", bob.toString())
                        .header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN.substring(0, 10)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(presenceService);
    }

    @Test
    void startupFailsWhenTheInternalTokenIsTooShort() {
        assertThatThrownBy(() -> new InternalTokenFilter("too-short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InternalTokenFilter(null)).isInstanceOf(IllegalStateException.class);
    }

    // ---- the user endpoints ----

    @Test
    void heartbeatPassesTheUserAndDeviceFromTheToken() throws Exception {
        mockMvc.perform(post("/api/presence/heartbeat").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(presenceService).heartbeat(alice, aliceDevice);
    }

    @Test
    void offlinePassesTheUserAndDeviceFromTheToken() throws Exception {
        mockMvc.perform(post("/api/presence/offline").header("Authorization", bearer()))
                .andExpect(status().isOk());

        verify(presenceService).goOffline(alice, aliceDevice);
    }

    @Test
    void presenceReturnsTheShapeOfTheContract() throws Exception {
        Instant last = Instant.parse("2026-11-04T10:15:30.123456Z");
        when(presenceService.presenceOf(alice, bob)).thenReturn(new PresenceResponse(bob, true, false, last));

        mockMvc.perform(get("/api/presence/" + bob).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(bob.toString()))
                .andExpect(jsonPath("$.data.visible").value(true))
                .andExpect(jsonPath("$.data.online").value(false))
                .andExpect(jsonPath("$.data.lastSeenAt").value("2026-11-04T10:15:30.123456Z"));
    }

    @Test
    void aHiddenStatusHasNullFields() throws Exception {
        when(presenceService.presenceOf(alice, bob)).thenReturn(PresenceResponse.hidden(bob));

        mockMvc.perform(get("/api/presence/" + bob).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(false))
                .andExpect(jsonPath("$.data.online").isEmpty())
                .andExpect(jsonPath("$.data.lastSeenAt").isEmpty());
    }

    @Test
    void aBadUserIdIs400() throws Exception {
        mockMvc.perform(get("/api/presence/not-a-uuid").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(presenceService);
    }

    @Test
    void whenADependencyFailsTheAnswerIs503InTheStandardFormat() throws Exception {
        when(presenceService.presenceOf(alice, bob)).thenThrow(new DependencyUnavailableException("down"));

        mockMvc.perform(get("/api/presence/" + bob).header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("DEPENDENCY_UNAVAILABLE"));
    }

    // ---- the internal endpoint ----

    @Test
    void onlineLookupPassesTheIdsAndReturnsTheOnlineOnes() throws Exception {
        when(presenceService.onlineAmong(any())).thenReturn(List.of(bob));

        mockMvc.perform(get("/internal/presence/online").param("userIds", alice + "," + bob)
                        .header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onlineUserIds[0]").value(bob.toString()));

        verify(presenceService).onlineAmong(List.of(alice, bob));
    }

    @Test
    void onlineLookupLimitsTheNumberOfIds() throws Exception {
        String ids200 = IntStream.range(0, 200).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(","));
        when(presenceService.onlineAmong(any())).thenReturn(List.of());

        mockMvc.perform(get("/internal/presence/online").param("userIds", ids200).header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/internal/presence/online").param("userIds", ids200 + "," + UUID.randomUUID())
                        .header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("VALIDATION"));
    }

    @Test
    void onlineLookupRefusesBadIds() throws Exception {
        for (String bad : List.of("", "x", alice + ",", "," + alice, alice + ",,"+ bob, alice + ", nope")) {
            mockMvc.perform(get("/internal/presence/online").param("userIds", bad).header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/internal/presence/online").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(presenceService);
    }
}
