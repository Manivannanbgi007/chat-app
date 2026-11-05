package com.chatapp.conversation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.chatapp.conversation.TestJwt;
import com.chatapp.conversation.dto.request.AddMembersRequest;
import com.chatapp.conversation.dto.request.UpdateGroupRequest;
import com.chatapp.conversation.dto.response.ConversationResponse;
import com.chatapp.conversation.dto.response.GroupInfoResponse;
import com.chatapp.conversation.dto.response.MembershipResponse;
import com.chatapp.conversation.dto.response.PreferencesResponse;
import com.chatapp.conversation.dto.response.PublicProfileResponse;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.MuteDuration;
import com.chatapp.conversation.enums.ParticipantRole;
import com.chatapp.conversation.exception.FieldValidationException;
import com.chatapp.conversation.exception.GlobalExceptionHandler;
import com.chatapp.conversation.exception.GroupAddNotAllowedException;
import com.chatapp.conversation.security.ApiSecurityErrorHandler;
import com.chatapp.conversation.security.SecurityConfig;
import com.chatapp.conversation.service.GroupService;
import com.chatapp.conversation.service.PreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The group and chat-settings endpoints: JSON in and out, validation, error format, the caller from the token. */
@WebMvcTest(value = {GroupController.class, PreferenceController.class}, properties = {
        "security.jwt.secret=" + TestJwt.SECRET,
        "security.jwt.issuer=" + TestJwt.ISSUER,
        "chatapp.internal.token=test-internal-token-test-internal-token"})
@Import({SecurityConfig.class, ApiSecurityErrorHandler.class, GlobalExceptionHandler.class})
class GroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GroupService groupService;
    @MockitoBean
    private PreferenceService preferenceService;

    private final UUID me = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();

    private ConversationResponse sampleGroup() {
        Instant created = Instant.parse("2026-11-04T10:00:00Z");
        return new ConversationResponse(id, ConversationType.GROUP, created, null,
                List.of(new PublicProfileResponse(me, "me", "Me", null, null)), null,
                new GroupInfoResponse("Trip", null, null, me, false, true, List.of(me)),
                new MembershipResponse(ParticipantRole.ADMIN, true, created, null),
                new PreferencesResponse(Instant.parse("9999-12-31T23:59:59Z"), false, true));
    }

    private MockHttpServletRequestBuilder asMe(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(j -> j.subject(me.toString()))).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void withoutATokenEveryEndpointIs401() throws Exception {
        mockMvc.perform(post("/api/conversations/" + id + "/leave")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/conversations/" + id + "/pin")).andExpect(status().isUnauthorized());
        verifyNoInteractions(groupService, preferenceService);
    }

    @Test
    void theAnswerHasTheDocumentedShape() throws Exception {
        when(groupService.leave(me, id)).thenReturn(sampleGroup());

        mockMvc.perform(asMe(post("/api/conversations/" + id + "/leave")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("GROUP"))
                .andExpect(jsonPath("$.data.otherUser").doesNotExist())
                .andExpect(jsonPath("$.data.group.name").value("Trip"))
                .andExpect(jsonPath("$.data.group.createdBy").value(me.toString()))
                .andExpect(jsonPath("$.data.group.onlyAdminsCanSend").value(false))
                .andExpect(jsonPath("$.data.group.onlyAdminsCanEdit").value(true))
                .andExpect(jsonPath("$.data.group.adminIds[0]").value(me.toString()))
                .andExpect(jsonPath("$.data.membership.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.membership.active").value(true))
                .andExpect(jsonPath("$.data.preferences.mutedUntil").value("9999-12-31T23:59:59Z"))
                .andExpect(jsonPath("$.data.preferences.archived").value(false))
                .andExpect(jsonPath("$.data.preferences.pinned").value(true));
    }

    // ---- members ----

    @Test
    void addMembersPassesTheCallerAndTheIds() throws Exception {
        when(groupService.addMembers(eq(me), eq(id), any())).thenReturn(sampleGroup());

        mockMvc.perform(asMe(post("/api/conversations/" + id + "/members"))
                        .content("{\"userIds\":[\"" + bob + "\"]}"))
                .andExpect(status().isOk());

        verify(groupService).addMembers(me, id, new AddMembersRequest(List.of(bob)));
    }

    @Test
    void addMembersValidatesTheList() throws Exception {
        String fiftyOne = IntStream.range(0, 51).mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));
        for (String body : List.of("{}", "{\"userIds\":[]}", "{\"userIds\":[null]}", "{\"userIds\":[" + fiftyOne + "]}",
                "{\"userIds\":[\"not-a-uuid\"]}")) {
            mockMvc.perform(asMe(post("/api/conversations/" + id + "/members")).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(groupService);
    }

    @Test
    void groupAddNotAllowedListsThePeopleInData() throws Exception {
        when(groupService.addMembers(eq(me), eq(id), any())).thenThrow(new GroupAddNotAllowedException(List.of(bob)));

        mockMvc.perform(asMe(post("/api/conversations/" + id + "/members"))
                        .content("{\"userIds\":[\"" + bob + "\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("GROUP_ADD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.data.userIds[0]").value(bob.toString()));
    }

    @Test
    void removeAndRoleUseThePathIds() throws Exception {
        when(groupService.removeMember(me, id, bob)).thenReturn(sampleGroup());
        when(groupService.changeRole(me, id, bob, ParticipantRole.ADMIN)).thenReturn(sampleGroup());

        mockMvc.perform(asMe(delete("/api/conversations/" + id + "/members/" + bob))).andExpect(status().isOk());
        mockMvc.perform(asMe(put("/api/conversations/" + id + "/members/" + bob + "/role"))
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        verify(groupService).removeMember(me, id, bob);
        verify(groupService).changeRole(me, id, bob, ParticipantRole.ADMIN);
    }

    @Test
    void roleMustBeAdminOrMember() throws Exception {
        mockMvc.perform(asMe(put("/api/conversations/" + id + "/members/" + bob + "/role")).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.role").exists());
        mockMvc.perform(asMe(put("/api/conversations/" + id + "/members/" + bob + "/role")).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(groupService);
    }

    // ---- group info ----

    @Test
    void patchPassesTheFieldsAndAServiceValidationErrorLooksLikeAnyValidationError() throws Exception {
        when(groupService.updateGroup(me, id, new UpdateGroupRequest(" ", null, null, null)))
                .thenThrow(new FieldValidationException("name", "The group needs a name"));
        when(groupService.updateGroup(me, id, new UpdateGroupRequest(null, null, true, null))).thenReturn(sampleGroup());

        mockMvc.perform(asMe(patch("/api/conversations/" + id + "/group")).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.name").value("The group needs a name"));
        mockMvc.perform(asMe(patch("/api/conversations/" + id + "/group")).content("{\"onlyAdminsCanSend\":true}"))
                .andExpect(status().isOk());
    }

    // ---- chat settings ----

    @Test
    void muteNeedsAKnownDuration() throws Exception {
        when(preferenceService.mute(me, id, MuteDuration.ONE_WEEK)).thenReturn(sampleGroup());

        mockMvc.perform(asMe(put("/api/conversations/" + id + "/mute")).content("{\"duration\":\"ONE_WEEK\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(asMe(put("/api/conversations/" + id + "/mute")).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.duration").exists());
        mockMvc.perform(asMe(put("/api/conversations/" + id + "/mute")).content("{\"duration\":\"FOREVER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putSwitchesOnAndDeleteSwitchesOff() throws Exception {
        when(preferenceService.archive(me, id)).thenReturn(sampleGroup());
        when(preferenceService.unarchive(me, id)).thenReturn(sampleGroup());
        when(preferenceService.pin(me, id)).thenReturn(sampleGroup());
        when(preferenceService.unpin(me, id)).thenReturn(sampleGroup());
        when(preferenceService.unmute(me, id)).thenReturn(sampleGroup());

        mockMvc.perform(asMe(put("/api/conversations/" + id + "/archive"))).andExpect(status().isOk());
        mockMvc.perform(asMe(delete("/api/conversations/" + id + "/archive"))).andExpect(status().isOk());
        mockMvc.perform(asMe(put("/api/conversations/" + id + "/pin"))).andExpect(status().isOk());
        mockMvc.perform(asMe(delete("/api/conversations/" + id + "/pin"))).andExpect(status().isOk());
        mockMvc.perform(asMe(delete("/api/conversations/" + id + "/mute"))).andExpect(status().isOk());

        verify(preferenceService).archive(me, id);
        verify(preferenceService).unarchive(me, id);
        verify(preferenceService).pin(me, id);
        verify(preferenceService).unpin(me, id);
        verify(preferenceService).unmute(me, id);
    }
}
