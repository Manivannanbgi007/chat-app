package com.chatapp.user.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.chatapp.user.TestJwt;
import com.chatapp.user.dto.response.ContactRelationResponse;
import com.chatapp.user.dto.response.PrivacyResponse;
import com.chatapp.user.dto.response.PublicProfileResponse;
import com.chatapp.user.enums.Visibility;
import com.chatapp.user.exception.GlobalExceptionHandler;
import com.chatapp.user.exception.ResourceNotFoundException;
import com.chatapp.user.security.ApiSecurityErrorHandler;
import com.chatapp.user.security.InternalSecurityConfig;
import com.chatapp.user.security.InternalTokenFilter;
import com.chatapp.user.security.SecurityConfig;
import com.chatapp.user.service.BlockService;
import com.chatapp.user.service.GroupAddPolicyService;
import com.chatapp.user.service.PresenceVisibilityService;
import com.chatapp.user.service.InternalLookupService;
import com.chatapp.user.service.PrivacyService;
import com.chatapp.user.service.ProfileService;
import com.chatapp.user.service.StatusAudienceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The /internal API is only for other services: it needs X-Internal-Token and must not accept a user JWT,
 * and the internal token must not open the normal /api endpoints. Both security chains are loaded for real.
 */
@WebMvcTest(value = {InternalController.class, UserController.class},
        properties = {
                "security.jwt.secret=" + TestJwt.SECRET,
                "security.jwt.issuer=" + TestJwt.ISSUER,
                "chatapp.internal.token=" + TestJwt.INTERNAL_TOKEN})
@Import({SecurityConfig.class, InternalSecurityConfig.class, ApiSecurityErrorHandler.class, GlobalExceptionHandler.class})
class InternalControllerTest {

    private static final String TOKEN_HEADER = InternalTokenFilter.HEADER;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InternalLookupService lookupService;
    @MockitoBean
    private PrivacyService privacyService;
    @MockitoBean
    private GroupAddPolicyService groupAddPolicyService;
    @MockitoBean
    private PresenceVisibilityService presenceVisibilityService;
    @MockitoBean
    private StatusAudienceService statusAudienceService;
    // needed because UserController is loaded too (to prove the internal token does not open /api)
    @MockitoBean
    private ProfileService profileService;
    @MockitoBean
    private BlockService blockService;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    // ---- who may call ----

    @Test
    void withoutTheInternalTokenEveryInternalEndpointAnswers401() throws Exception {
        mockMvc.perform(get("/internal/blocks").param("a", a.toString()).param("b", b.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get("/internal/users/" + a + "/privacy"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/users/summaries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + a + "\",\"userIds\":[]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/users/presence-visibility").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + a + "\",\"targetId\":\"" + b + "\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(lookupService, privacyService, presenceVisibilityService);
    }

    @Test
    void wrongInternalTokenAnswers401() throws Exception {
        mockMvc.perform(get("/internal/blocks").param("a", a.toString()).param("b", b.toString())
                        .header(TOKEN_HEADER, "wrong-token-wrong-token-wrong-1234"))
                .andExpect(status().isUnauthorized());
        // a token that is only a prefix of the real one, and an empty one
        mockMvc.perform(get("/internal/blocks").param("a", a.toString()).param("b", b.toString())
                        .header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN.substring(0, 10)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/blocks").param("a", a.toString()).param("b", b.toString())
                        .header(TOKEN_HEADER, ""))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(lookupService);
    }

    @Test
    void aValidUserJwtDoesNotOpenInternalEndpoints() throws Exception {
        mockMvc.perform(get("/internal/users/" + a + "/privacy")
                        .header("Authorization", "Bearer " + TestJwt.token(a)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(privacyService);
    }

    @Test
    void theInternalTokenDoesNotOpenTheNormalApi() throws Exception {
        mockMvc.perform(get("/api/users/me").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profileService);
    }

    @Test
    void startupFailsWhenTheTokenIsTooShort() {
        assertThatThrownBy(() -> new InternalTokenFilter("too-short"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InternalTokenFilter(null))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- status audience (Phase 8) ----

    @Test
    void contactOwnersReturnsTheIdsFromTheService() throws Exception {
        when(statusAudienceService.contactOwners(a)).thenReturn(List.of(b));

        mockMvc.perform(get("/internal/users/" + a + "/contact-owners").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ownerIds.length()").value(1))
                .andExpect(jsonPath("$.data.ownerIds[0]").value(b.toString()));
    }

    @Test
    void contactRelationAnswersBothFlags() throws Exception {
        when(statusAudienceService.relation(a, b)).thenReturn(new ContactRelationResponse(true, false));

        mockMvc.perform(get("/internal/contacts/relation").param("ownerId", a.toString()).param("viewerId", b.toString())
                        .header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contact").value(true))
                .andExpect(jsonPath("$.data.blocked").value(false));
        verify(statusAudienceService).relation(a, b);
    }

    @Test
    void contactRelationNeedsTwoValidUuids() throws Exception {
        mockMvc.perform(get("/internal/contacts/relation").param("ownerId", a.toString())
                        .header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/contacts/relation").param("ownerId", "nope").param("viewerId", b.toString())
                        .header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/users/not-a-uuid/contact-owners").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(statusAudienceService);
    }

    @Test
    void statusAudienceEndpointsNeedTheInternalToken() throws Exception {
        mockMvc.perform(get("/internal/users/" + a + "/contact-owners"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/contacts/relation").param("ownerId", a.toString()).param("viewerId", b.toString())
                        .header("Authorization", "Bearer " + TestJwt.token(a)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(statusAudienceService);
    }

    // ---- GET /internal/blocks ----

    @Test
    void blocksAnswersTrueOrFalse() throws Exception {
        when(lookupService.isBlockedEitherWay(a, b)).thenReturn(true);

        mockMvc.perform(get("/internal/blocks").param("a", a.toString()).param("b", b.toString())
                        .header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.blocked").value(true));

        mockMvc.perform(get("/internal/blocks").param("a", b.toString()).param("b", UUID.randomUUID().toString())
                        .header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blocked").value(false));
    }

    @Test
    void blocksNeedsTwoValidUuids() throws Exception {
        mockMvc.perform(get("/internal/blocks").param("a", a.toString())
                        .header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/blocks").param("a", a.toString()).param("b", "not-a-uuid")
                        .header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isBadRequest());
    }

    // ---- GET /internal/users/{id}/privacy ----

    @Test
    void privacyReturnsTheSettings() throws Exception {
        when(privacyService.getPrivacy(a)).thenReturn(
                new PrivacyResponse(Visibility.CONTACTS, Visibility.NOBODY, Visibility.EVERYONE, Visibility.CONTACTS, false));

        mockMvc.perform(get("/internal/users/" + a + "/privacy").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastSeen").value("CONTACTS"))
                .andExpect(jsonPath("$.data.profilePhoto").value("NOBODY"))
                .andExpect(jsonPath("$.data.readReceipts").value(false));
    }

    @Test
    void privacyOfAnUnknownUserIs404() throws Exception {
        when(privacyService.getPrivacy(a)).thenThrow(new ResourceNotFoundException("Profile not found"));

        mockMvc.perform(get("/internal/users/" + a + "/privacy").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ---- POST /internal/users/summaries ----

    @Test
    void summariesPassesViewerAndIdsToTheService() throws Exception {
        when(lookupService.summaries(eq(a), any())).thenReturn(
                List.of(new PublicProfileResponse(b, "bob", "Bob", null, null)));

        mockMvc.perform(post("/internal/users/summaries").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + a + "\",\"userIds\":[\"" + b + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(b.toString()))
                .andExpect(jsonPath("$.data[0].displayName").value("Bob"));

        verify(lookupService).summaries(a, List.of(b));
    }

    @Test
    void summariesRejectsMoreThan100Ids() throws Exception {
        String ids = IntStream.range(0, 101)
                .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/internal/users/summaries").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + a + "\",\"userIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.userIds").exists());
        verifyNoInteractions(lookupService);
    }

    @Test
    void summariesAcceptsExactly100Ids() throws Exception {
        String ids = IntStream.range(0, 100)
                .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));
        when(lookupService.summaries(eq(a), any())).thenReturn(List.of());

        mockMvc.perform(post("/internal/users/summaries").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + a + "\",\"userIds\":[" + ids + "]}"))
                .andExpect(status().isOk());
    }

    @Test
    void summariesRequiresViewerAndIds() throws Exception {
        mockMvc.perform(post("/internal/users/summaries").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[\"" + b + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.viewerId").exists());
        mockMvc.perform(post("/internal/users/summaries").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + a + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.userIds").exists());
        mockMvc.perform(post("/internal/users/summaries").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + a + "\",\"userIds\":[null]}"))
                .andExpect(status().isBadRequest());
    }

    // ---- POST /internal/users/presence-visibility ----

    @Test
    void presenceVisibilityAnswersTrueOrFalse() throws Exception {
        when(presenceVisibilityService.canSee(a, b)).thenReturn(true);
        when(presenceVisibilityService.canSee(b, a)).thenReturn(false);

        mockMvc.perform(post("/internal/users/presence-visibility").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + a + "\",\"targetId\":\"" + b + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.visible").value(true));

        mockMvc.perform(post("/internal/users/presence-visibility").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + b + "\",\"targetId\":\"" + a + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(false));
    }

    @Test
    void presenceVisibilityNeedsBothIds() throws Exception {
        mockMvc.perform(post("/internal/users/presence-visibility").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":\"" + b + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.viewerId").exists());
        mockMvc.perform(post("/internal/users/presence-visibility").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + a + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.targetId").exists());
        mockMvc.perform(post("/internal/users/presence-visibility").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"nope\",\"targetId\":\"" + b + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(presenceVisibilityService);
    }

    @Test
    void aUserJwtDoesNotOpenPresenceVisibility() throws Exception {
        mockMvc.perform(post("/internal/users/presence-visibility")
                        .header("Authorization", "Bearer " + TestJwt.token(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + a + "\",\"targetId\":\"" + b + "\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(presenceVisibilityService);
    }

    // ---- POST /internal/users/group-add-check ----

    @Test
    void groupAddCheckReturnsTheRefusedUsers() throws Exception {
        when(groupAddPolicyService.notAllowed(eq(a), any())).thenReturn(List.of(b));

        mockMvc.perform(post("/internal/users/group-add-check").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"" + a + "\",\"userIds\":[\"" + b + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notAllowed[0]").value(b.toString()));

        verify(groupAddPolicyService).notAllowed(a, List.of(b));
    }

    @Test
    void groupAddCheckNeedsTheInternalToken() throws Exception {
        mockMvc.perform(post("/internal/users/group-add-check")
                        .header("Authorization", "Bearer " + TestJwt.token(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"" + a + "\",\"userIds\":[\"" + b + "\"]}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(groupAddPolicyService);
    }

    @Test
    void groupAddCheckValidatesTheBody() throws Exception {
        String tooMany = IntStream.range(0, 257)
                .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));
        List<String> badBodies = List.of(
                "{\"userIds\":[\"" + b + "\"]}",                     // no actor
                "{\"actorId\":\"" + a + "\",\"userIds\":[]}",         // empty list
                "{\"actorId\":\"" + a + "\"}",                        // no list
                "{\"actorId\":\"" + a + "\",\"userIds\":[null]}",     // a null id
                "{\"actorId\":\"" + a + "\",\"userIds\":[" + tooMany + "]}");   // more than 256
        for (String body : badBodies) {
            mockMvc.perform(post("/internal/users/group-add-check").header(TOKEN_HEADER, TestJwt.INTERNAL_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(groupAddPolicyService);
    }
}
