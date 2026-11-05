package com.chatapp.conversation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.TestJwt;
import com.chatapp.conversation.dto.request.CreateConversationRequest;
import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.dto.response.MembershipResponse;
import com.chatapp.conversation.dto.response.PreferencesResponse;
import com.chatapp.conversation.dto.response.PublicProfileResponse;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.ParticipantRole;
import com.chatapp.conversation.exception.ForbiddenException;
import com.chatapp.conversation.exception.GlobalExceptionHandler;
import com.chatapp.conversation.exception.ResourceNotFoundException;
import com.chatapp.conversation.security.ApiSecurityErrorHandler;
import com.chatapp.conversation.security.SecurityConfig;
import com.chatapp.conversation.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// The secrets are set here so the test never depends on a local infra/.env file.
@WebMvcTest(value = ConversationController.class, properties = {
        "security.jwt.secret=" + TestJwt.SECRET,
        "security.jwt.issuer=" + TestJwt.ISSUER,
        "chatapp.internal.token=test-internal-token-test-internal-token"})
@Import({SecurityConfig.class, ApiSecurityErrorHandler.class, GlobalExceptionHandler.class})
class ConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationService conversationService;

    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private ConversationResponse sample(UUID id) {
        PublicProfileResponse otherProfile = new PublicProfileResponse(other, "bob", "Bob", null, null);
        Instant created = Instant.parse("2026-11-04T10:00:00Z");
        return new ConversationResponse(id, ConversationType.PRIVATE, created, null,
                List.of(new PublicProfileResponse(me, "me", "Me", null, null), otherProfile), otherProfile, null,
                new MembershipResponse(ParticipantRole.MEMBER, true, created, null),
                new PreferencesResponse(null, false, false));
    }

    private String body(String type, Object userId) {
        return "{\"type\":" + (type == null ? "null" : "\"" + type + "\"") + ",\"userId\":"
                + (userId == null ? "null" : "\"" + userId + "\"") + "}";
    }

    // ---- security ----

    @Test
    void withoutTokenEveryEndpointAnswers401InStandardFormat() throws Exception {
        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Authentication required"));
        mockMvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON).content(body("PRIVATE", other)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/conversations/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageExpiredAndForgedTokensAreRejected() throws Exception {
        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());

        String expired = TestJwt.token(me, TestJwt.SECRET, TestJwt.ISSUER, Duration.ofMinutes(-10));
        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());

        String forged = TestJwt.token(me, "another-secret-another-secret-another-1234", TestJwt.ISSUER, Duration.ofMinutes(5));
        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());

        String wrongIssuer = TestJwt.token(me, TestJwt.SECRET, "someone-else", Duration.ofMinutes(5));
        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + wrongIssuer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theInternalTokenDoesNotWorkOnTheUserApi() throws Exception {
        mockMvc.perform(get("/api/conversations").header("X-Internal-Token", "test-internal-token-test-internal-token"))
                .andExpect(status().isUnauthorized());
    }

    // ---- create ----

    @Test
    void createReturns201ForANewConversationAndUsesTheCallerFromTheToken() throws Exception {
        UUID id = UUID.randomUUID();
        when(conversationService.create(eq(me), any(CreateConversationRequest.class)))
                .thenReturn(new ConversationService.CreateResult(sample(id), true));

        mockMvc.perform(post("/api/conversations").with(jwt().jwt(j -> j.subject(me.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body("PRIVATE", other)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.otherUser.displayName").value("Bob"))
                .andExpect(jsonPath("$.data.participants.length()").value(2));

        verify(conversationService).create(eq(me), eq(new CreateConversationRequest(ConversationType.PRIVATE, other)));
    }

    @Test
    void createReturns200WhenTheConversationAlreadyExists() throws Exception {
        when(conversationService.create(eq(me), any()))
                .thenReturn(new ConversationService.CreateResult(sample(UUID.randomUUID()), false));

        mockMvc.perform(post("/api/conversations").with(jwt().jwt(j -> j.subject(me.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body("PRIVATE", other)))
                .andExpect(status().isOk());
    }

    @Test
    void createWithoutTypeIsAValidationError() throws Exception {
        mockMvc.perform(post("/api/conversations").with(jwt().jwt(j -> j.subject(me.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, other)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.type").exists());
    }

    @Test
    void createWithBrokenInputIs400() throws Exception {
        mockMvc.perform(post("/api/conversations").with(jwt().jwt(j -> j.subject(me.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body("PRIVATE", "not-a-uuid")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/conversations").with(jwt().jwt(j -> j.subject(me.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body("CHANNEL", other)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/conversations").with(jwt().jwt(j -> j.subject(me.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blockedUsersGet403WithTheErrorCode() throws Exception {
        when(conversationService.create(eq(me), any()))
                .thenThrow(new ForbiddenException("You cannot message this user", "MESSAGING_NOT_ALLOWED"));

        mockMvc.perform(post("/api/conversations").with(jwt().jwt(j -> j.subject(me.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body("PRIVATE", other)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("MESSAGING_NOT_ALLOWED"));
    }

    // ---- read ----

    @Test
    void listReturnsTheCallersConversations() throws Exception {
        when(conversationService.list(me)).thenReturn(List.of(sample(UUID.randomUUID()), sample(UUID.randomUUID())));

        mockMvc.perform(get("/api/conversations").with(jwt().jwt(j -> j.subject(me.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void getByNonParticipantIs404() throws Exception {
        UUID id = UUID.randomUUID();
        when(conversationService.get(me, id)).thenThrow(new ResourceNotFoundException("Conversation not found"));

        mockMvc.perform(get("/api/conversations/" + id).with(jwt().jwt(j -> j.subject(me.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Conversation not found"));
    }

    @Test
    void getWithABrokenIdIs400() throws Exception {
        mockMvc.perform(get("/api/conversations/not-a-uuid").with(jwt().jwt(j -> j.subject(me.toString()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturnsTheConversationForAParticipant() throws Exception {
        UUID id = UUID.randomUUID();
        when(conversationService.get(me, id)).thenReturn(sample(id));

        mockMvc.perform(get("/api/conversations/" + id).with(jwt().jwt(j -> j.subject(me.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()));
    }
}
