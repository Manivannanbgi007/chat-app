package com.chatapp.conversation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.conversation.support.AbstractIntegrationTest;
import com.chatapp.conversation.support.FakeUserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** GET /internal/conversations/{id}: the endpoint other services (message-service) use to ask who takes part. */
class InternalEndpointTest extends AbstractIntegrationTest {

    private MvcResult internalGet(String path, String token) throws Exception {
        var request = get(path);
        if (token != null) {
            request.header("X-Internal-Token", token);
        }
        return mockMvc.perform(request).andReturn();
    }

    @Test
    void withTheTokenTheActiveParticipantsAreReturned() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        String id = field(startConversation(alice, bob), "$.data.id");

        MvcResult result = internalGet("/internal/conversations/" + id, FakeUserService.TOKEN);

        assertStatus(result, 200);
        assertThat(field(result, "$.data.id")).isEqualTo(id);
        assertThat(field(result, "$.data.type")).isEqualTo("PRIVATE");
        List<String> participantIds = JsonPath.read(result.getResponse().getContentAsString(), "$.data.participantIds");
        assertThat(participantIds).containsExactlyInAnyOrder(alice.toString(), bob.toString());
    }

    @Test
    void theAnswerHasAdminsTheSendRuleAndEveryMembershipPeriod() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        String privateId = field(startConversation(alice, bob), "$.data.id");
        String groupId = groupId(alice, "Internal", bob);
        assertStatus(patchAs(alice, "/api/conversations/" + groupId + "/group", Map.of("onlyAdminsCanSend", true)), 200);

        MvcResult privateChat = internalGet("/internal/conversations/" + privateId, FakeUserService.TOKEN);
        assertThat(field(privateChat, "$.data.adminIds.length()")).isEqualTo("0");
        assertThat(field(privateChat, "$.data.onlyAdminsCanSend")).isEqualTo("false");
        assertThat(field(privateChat, "$.data.members.length()")).isEqualTo("2");
        // both joined when the chat was created (the very same time, so message-service sees no gap)
        assertThat(field(privateChat, "$.data.members[0].joinedAt"))
                .isEqualTo(field(privateChat, "$.data.members[1].joinedAt"));
        assertThat(field(privateChat, "$.data.members[0].leftAt")).isNull();

        MvcResult group = internalGet("/internal/conversations/" + groupId, FakeUserService.TOKEN);
        assertThat(field(group, "$.data.type")).isEqualTo("GROUP");
        List<String> adminIds = JsonPath.read(group.getResponse().getContentAsString(), "$.data.adminIds");
        assertThat(adminIds).containsExactly(alice.toString());
        assertThat(field(group, "$.data.onlyAdminsCanSend")).isEqualTo("true");
        List<String> periodUsers = JsonPath.read(group.getResponse().getContentAsString(), "$.data.members[*].userId");
        assertThat(periodUsers).containsExactlyInAnyOrder(alice.toString(), bob.toString());
    }

    @Test
    void whoLeftIsNotListed() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        String id = field(startConversation(alice, bob), "$.data.id");
        jdbc.update("UPDATE conversation_participants SET active = FALSE WHERE conversation_id = ? AND user_id = ?",
                id, bob.toString());

        MvcResult result = internalGet("/internal/conversations/" + id, FakeUserService.TOKEN);

        List<String> participantIds = JsonPath.read(result.getResponse().getContentAsString(), "$.data.participantIds");
        assertThat(participantIds).containsExactly(alice.toString());
    }

    @Test
    void theAnswerHasTheGroupNameAndNoNameForPrivateChats() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        String privateId = field(startConversation(alice, bob), "$.data.id");
        String groupId = groupId(alice, "Trip to Goa", bob);

        assertThat(field(internalGet("/internal/conversations/" + privateId, FakeUserService.TOKEN), "$.data.name")).isNull();
        assertThat(field(internalGet("/internal/conversations/" + groupId, FakeUserService.TOKEN), "$.data.name"))
                .isEqualTo("Trip to Goa");
    }

    @Test
    void mutedUsersAreOnlyThoseWhoseMuteIsActiveNow() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        UUID carol = USER_SERVICE.addUser("Carol Test");
        String groupId = groupId(alice, "Mutes", bob, carol);
        String mute = "/api/conversations/" + groupId + "/mute";
        assertStatus(putAs(alice, mute, Map.of("duration", "ALWAYS")), 200);
        assertStatus(putAs(bob, mute, Map.of("duration", "ONE_WEEK")), 200);
        // carol muted a while ago: the mute has ended
        assertStatus(putAs(carol, mute, Map.of("duration", "ONE_WEEK")), 200);
        jdbc.update("UPDATE conversation_participants SET muted_until = UTC_TIMESTAMP(6) - INTERVAL 1 HOUR "
                + "WHERE conversation_id = ? AND user_id = ?", groupId, carol.toString());

        MvcResult result = internalGet("/internal/conversations/" + groupId + "/muted-users", FakeUserService.TOKEN);

        assertStatus(result, 200);
        List<String> muted = JsonPath.read(result.getResponse().getContentAsString(), "$.data.mutedUserIds");
        assertThat(muted).containsExactlyInAnyOrder(alice.toString(), bob.toString());
    }

    @Test
    void membersWhoLeftAreNotListedAsMuted() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        String groupId = groupId(alice, "Mutes", bob);
        assertStatus(putAs(bob, "/api/conversations/" + groupId + "/mute", Map.of("duration", "ALWAYS")), 200);
        jdbc.update("UPDATE conversation_participants SET active = FALSE WHERE conversation_id = ? AND user_id = ?",
                groupId, bob.toString());

        MvcResult result = internalGet("/internal/conversations/" + groupId + "/muted-users", FakeUserService.TOKEN);

        List<String> muted = JsonPath.read(result.getResponse().getContentAsString(), "$.data.mutedUserIds");
        assertThat(muted).isEmpty();
    }

    @Test
    void mutedUsersNeedTheTokenAndAKnownConversation() throws Exception {
        String path = "/internal/conversations/" + UUID.randomUUID() + "/muted-users";

        assertStatus(internalGet(path, null), 401);
        assertStatus(internalGet(path, FakeUserService.TOKEN), 404);
    }

    @Test
    void withoutOrWithAWrongTokenIs401() throws Exception {
        String path = "/internal/conversations/" + UUID.randomUUID();

        assertStatus(internalGet(path, null), 401);
        assertStatus(internalGet(path, "wrong-token"), 401);
        assertStatus(internalGet(path, FakeUserService.TOKEN + "x"), 401);
    }

    @Test
    void aUserJwtIsNotAcceptedOnTheInternalEndpoint() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Test");
        UUID bob = USER_SERVICE.addUser("Bob Test");
        String id = field(startConversation(alice, bob), "$.data.id");

        MvcResult result = mockMvc.perform(get("/internal/conversations/" + id)
                .header("Authorization", bearer(alice))).andReturn();

        assertStatus(result, 401);
    }

    @Test
    void theInternalTokenIsNotAcceptedOnTheUserApi() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/conversations")
                .header("X-Internal-Token", FakeUserService.TOKEN)).andReturn();

        assertStatus(result, 401);
    }

    @Test
    void unknownConversationIs404() throws Exception {
        assertStatus(internalGet("/internal/conversations/" + UUID.randomUUID(), FakeUserService.TOKEN), 404);
    }
}
