package com.chatapp.conversation.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.TestJwt;
import com.chatapp.conversation.dto.response.InternalConversationResponse;
import com.chatapp.conversation.dto.response.MemberPeriodResponse;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.exception.GlobalExceptionHandler;
import com.chatapp.conversation.exception.ResourceNotFoundException;
import com.chatapp.conversation.security.ApiSecurityErrorHandler;
import com.chatapp.conversation.security.SecurityConfig;
import com.chatapp.conversation.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The internal endpoint is for services only: it needs X-Internal-Token and refuses user tokens. */
@WebMvcTest(value = InternalConversationController.class, properties = {
        "security.jwt.secret=" + TestJwt.SECRET,
        "security.jwt.issuer=" + TestJwt.ISSUER,
        "chatapp.internal.token=" + InternalConversationControllerTest.TOKEN})
@Import({SecurityConfig.class, ApiSecurityErrorHandler.class, GlobalExceptionHandler.class})
class InternalConversationControllerTest {

    static final String TOKEN = "test-internal-token-test-internal-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationService conversationService;

    private final UUID id = UUID.randomUUID();

    @Test
    void withTheInternalTokenTheParticipantsAreReturned() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Instant joined = Instant.parse("2026-11-04T10:00:00.123456Z");
        Instant left = Instant.parse("2026-11-04T11:00:00Z");
        when(conversationService.getForInternalUse(id)).thenReturn(new InternalConversationResponse(
                id, ConversationType.GROUP, "Trip to Goa", List.of(a, b), List.of(a), true, List.of(
                        new MemberPeriodResponse(a, joined, null),
                        new MemberPeriodResponse(b, joined, left),
                        new MemberPeriodResponse(b, left.plusSeconds(60), null))));

        mockMvc.perform(get("/internal/conversations/" + id).header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.type").value("GROUP"))
                .andExpect(jsonPath("$.data.name").value("Trip to Goa"))
                .andExpect(jsonPath("$.data.participantIds[0]").value(a.toString()))
                .andExpect(jsonPath("$.data.participantIds.length()").value(2))
                .andExpect(jsonPath("$.data.adminIds[0]").value(a.toString()))
                .andExpect(jsonPath("$.data.onlyAdminsCanSend").value(true))
                .andExpect(jsonPath("$.data.members.length()").value(3))
                .andExpect(jsonPath("$.data.members[0].userId").value(a.toString()))
                .andExpect(jsonPath("$.data.members[0].joinedAt").value("2026-11-04T10:00:00.123456Z"))
                .andExpect(jsonPath("$.data.members[0].leftAt").doesNotExist())
                .andExpect(jsonPath("$.data.members[1].leftAt").value("2026-11-04T11:00:00Z"));
    }

    @Test
    void withoutTheTokenIs401InStandardFormat() throws Exception {
        mockMvc.perform(get("/internal/conversations/" + id))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void aWrongOrAlmostRightTokenIs401() throws Exception {
        mockMvc.perform(get("/internal/conversations/" + id).header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/conversations/" + id).header("X-Internal-Token", TOKEN + "x"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/conversations/" + id).header("X-Internal-Token", ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aUserJwtDoesNotWorkOnTheInternalEndpoint() throws Exception {
        String userToken = TestJwt.token(UUID.randomUUID(), TestJwt.SECRET, TestJwt.ISSUER, Duration.ofMinutes(5));

        mockMvc.perform(get("/internal/conversations/" + id).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownConversationIs404() throws Exception {
        when(conversationService.getForInternalUse(id)).thenThrow(new ResourceNotFoundException("Conversation not found"));

        mockMvc.perform(get("/internal/conversations/" + id).header("X-Internal-Token", TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void mutedUsersAreReturnedWithTheInternalToken() throws Exception {
        UUID a = UUID.randomUUID();
        when(conversationService.getMutedUserIdsForInternalUse(id)).thenReturn(List.of(a));

        mockMvc.perform(get("/internal/conversations/" + id + "/muted-users").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mutedUserIds[0]").value(a.toString()))
                .andExpect(jsonPath("$.data.mutedUserIds.length()").value(1));
    }

    @Test
    void mutedUsersNeedTheInternalToken() throws Exception {
        mockMvc.perform(get("/internal/conversations/" + id + "/muted-users"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/conversations/" + id + "/muted-users").header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }
}
