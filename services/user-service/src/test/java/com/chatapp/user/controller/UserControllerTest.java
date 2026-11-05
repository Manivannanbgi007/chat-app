package com.chatapp.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.user.TestJwt;
import com.chatapp.user.dto.response.MyProfileResponse;
import com.chatapp.user.dto.response.PrivacyResponse;
import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.enums.AccountStatus;
import com.chatapp.user.enums.Visibility;
import com.chatapp.user.exception.GlobalExceptionHandler;
import com.chatapp.user.exception.ResourceNotFoundException;
import com.chatapp.user.security.ApiSecurityErrorHandler;
import com.chatapp.user.security.SecurityConfig;
import com.chatapp.user.service.BlockService;
import com.chatapp.user.service.PrivacyService;
import com.chatapp.user.service.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// The secret is set here so the test never depends on a local infra/.env file.
@WebMvcTest(value = UserController.class,
        properties = {"security.jwt.secret=" + TestJwt.SECRET, "security.jwt.issuer=" + TestJwt.ISSUER})
@Import({SecurityConfig.class, ApiSecurityErrorHandler.class, GlobalExceptionHandler.class})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProfileService profileService;
    @MockitoBean
    private BlockService blockService;
    @MockitoBean
    private PrivacyService privacyService;

    private final UUID me = UUID.randomUUID();

    // ---- security ----

    @Test
    void withoutTokenAnswers401InStandardFormat() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void garbageTokenAnswers401() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void tokenSignedWithAnotherSecretAnswers401() throws Exception {
        String forged = TestJwt.token(me, "another-secret-another-secret-another-1234", TestJwt.ISSUER,
                Duration.ofMinutes(5));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenAnswers401() throws Exception {
        String expired = TestJwt.token(me, TestJwt.SECRET, TestJwt.ISSUER, Duration.ofMinutes(-5));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnotherIssuerAnswers401() throws Exception {
        String wrongIssuer = TestJwt.token(me, TestJwt.SECRET, "someone-else", Duration.ofMinutes(5));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + wrongIssuer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        when(profileService.getMyProfile(me)).thenReturn(myProfile());

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + TestJwt.token(me)))
                .andExpect(status().isOk());
    }

    // ---- caller identity ----

    @Test
    void callerIdComesFromTheTokenNotFromTheRequest() throws Exception {
        UUID somebodyElse = UUID.randomUUID();
        when(profileService.getMyProfile(me)).thenReturn(myProfile());

        mockMvc.perform(get("/api/users/me")
                        .param("userId", somebodyElse.toString())
                        .header("X-User-Id", somebodyElse.toString())
                        .with(jwtOf(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(me.toString()));

        verify(profileService).getMyProfile(me);
    }

    // ---- my profile ----

    @Test
    void getMeReturnsTheProfileInStandardFormat() throws Exception {
        when(profileService.getMyProfile(me)).thenReturn(myProfile());

        mockMvc.perform(get("/api/users/me").with(jwtOf(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.email").value("alice@example.com"));
    }

    @Test
    void getMeAnswers404WhenProfileDoesNotExistYet() throws Exception {
        when(profileService.getMyProfile(me)).thenThrow(new ResourceNotFoundException("User not found"));

        mockMvc.perform(get("/api/users/me").with(jwtOf(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void updateMeRejectsBlankDisplayName() throws Exception {
        mockMvc.perform(put("/api/users/me").with(jwtOf(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  \",\"about\":\"hi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.displayName").exists());
    }

    @Test
    void updateMeRejectsTooLongAbout() throws Exception {
        mockMvc.perform(put("/api/users/me").with(jwtOf(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alice\",\"about\":\"" + "x".repeat(140) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.about").exists());
    }

    // ---- search and other users ----

    @Test
    void searchRequiresTheQueryParameter() throws Exception {
        mockMvc.perform(get("/api/users/search").with(jwtOf(me)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchPassesTextAndCallerToTheService() throws Exception {
        when(profileService.search(me, "bo")).thenReturn(List.of(
                new PublicProfileResponse(UUID.randomUUID(), "bob", "Bob", null, null)));

        mockMvc.perform(get("/api/users/search").param("q", "bo").with(jwtOf(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("bob"))
                .andExpect(jsonPath("$.data[0].email").doesNotExist());
    }

    @Test
    void userIdMustBeAUuid() throws Exception {
        mockMvc.perform(get("/api/users/not-a-uuid").with(jwtOf(me)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOtherUserUsesCallerAsViewer() throws Exception {
        UUID other = UUID.randomUUID();
        when(profileService.getPublicProfile(me, other))
                .thenReturn(new PublicProfileResponse(other, "bob", "Bob", null, null));

        mockMvc.perform(get("/api/users/" + other).with(jwtOf(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(other.toString()));
    }

    // ---- block ----

    @Test
    void blockAndUnblockUseTheCallerAsBlocker() throws Exception {
        UUID target = UUID.randomUUID();

        mockMvc.perform(post("/api/users/" + target + "/block").with(jwtOf(me)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/users/" + target + "/block").with(jwtOf(me)))
                .andExpect(status().isOk());

        verify(blockService).block(me, target);
        verify(blockService).unblock(me, target);
    }

    @Test
    void blockRequiresAToken() throws Exception {
        mockMvc.perform(post("/api/users/" + UUID.randomUUID() + "/block"))
                .andExpect(status().isUnauthorized());
    }

    // ---- privacy ----

    @Test
    void privacyUpdateRejectsMissingFields() throws Exception {
        mockMvc.perform(put("/api/users/me/privacy").with(jwtOf(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastSeen\":\"EVERYONE\",\"profilePhoto\":\"CONTACTS\","
                                + "\"about\":\"NOBODY\",\"groupAdd\":\"EVERYONE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.readReceipts").exists());
    }

    @Test
    void privacyUpdateRejectsUnknownVisibility() throws Exception {
        mockMvc.perform(put("/api/users/me/privacy").with(jwtOf(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastSeen\":\"WORLD\",\"profilePhoto\":\"CONTACTS\","
                                + "\"about\":\"NOBODY\",\"groupAdd\":\"EVERYONE\",\"readReceipts\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void privacyUpdateWorks() throws Exception {
        PrivacyResponse response = new PrivacyResponse(Visibility.EVERYONE, Visibility.CONTACTS,
                Visibility.NOBODY, Visibility.EVERYONE, false);
        when(privacyService.updatePrivacy(any(), any())).thenReturn(response);

        mockMvc.perform(put("/api/users/me/privacy").with(jwtOf(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastSeen\":\"EVERYONE\",\"profilePhoto\":\"CONTACTS\","
                                + "\"about\":\"NOBODY\",\"groupAdd\":\"EVERYONE\",\"readReceipts\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profilePhoto").value("CONTACTS"))
                .andExpect(jsonPath("$.data.readReceipts").value(false));
    }

    // ---- helpers ----

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtOf(UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()));
    }

    private MyProfileResponse myProfile() {
        return new MyProfileResponse(me, "alice", "Alice", "alice@example.com", null, null, null,
                AccountStatus.ACTIVE, Instant.now(), Instant.now());
    }
}
