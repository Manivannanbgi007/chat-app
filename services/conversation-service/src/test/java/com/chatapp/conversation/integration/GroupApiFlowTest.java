package com.chatapp.conversation.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.chatapp.conversation.support.AbstractIntegrationTest;
import com.chatapp.conversation.support.FakeUserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Groups on a real MySQL and Kafka, with the fake user-service (API_DOCUMENTATION.md section 4). */
class GroupApiFlowTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------------ create

    @Test
    void creatingAGroupMakesMeAdminAndEveryMemberSeesIt() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Group");
        UUID bob = USER_SERVICE.addUser("Bob Group");
        UUID carol = USER_SERVICE.addUser("Carol Group");
        UUID stranger = USER_SERVICE.addUser("Stranger Group");

        MvcResult created = postAs(alice, "/api/conversations", Map.of("type", "GROUP", "name", "  Trip to Goa  ",
                "description", "Beach week", "memberIds", List.of(bob.toString(), carol.toString(), bob.toString())));

        assertStatus(created, 201);
        String id = field(created, "$.data.id");
        assertThat(field(created, "$.data.type")).isEqualTo("GROUP");
        assertThat(field(created, "$.data.otherUser")).isNull();
        assertThat(field(created, "$.data.group.name")).isEqualTo("Trip to Goa");
        assertThat(field(created, "$.data.group.description")).isEqualTo("Beach week");
        assertThat(field(created, "$.data.group.imageUrl")).isNull();
        assertThat(field(created, "$.data.group.createdBy")).isEqualTo(alice.toString());
        assertThat(field(created, "$.data.group.onlyAdminsCanSend")).isEqualTo("false");
        assertThat(field(created, "$.data.group.onlyAdminsCanEdit")).isEqualTo("false");
        assertThat(ids(created, "$.data.group.adminIds[*]")).containsExactly(alice.toString());
        assertThat(ids(created, "$.data.participants[*].id"))
                .containsExactlyInAnyOrder(alice.toString(), bob.toString(), carol.toString());
        assertThat(field(created, "$.data.membership.role")).isEqualTo("ADMIN");
        assertThat(field(created, "$.data.membership.active")).isEqualTo("true");
        assertThat(field(created, "$.data.preferences.pinned")).isEqualTo("false");

        MvcResult bobsList = getAs(bob, "/api/conversations");
        assertThat(field(bobsList, "$.data[0].id")).isEqualTo(id);
        assertThat(field(bobsList, "$.data[0].membership.role")).isEqualTo("MEMBER");
        assertStatus(getAs(carol, "/api/conversations/" + id), 200);
        assertStatus(getAs(stranger, "/api/conversations/" + id), 404);

        assertThat(countRows("conversation_participants", "conversation_id", id)).isEqualTo(3);
        assertThat(countRows("membership_periods", "conversation_id", id)).isEqualTo(3);
        assertThat(countRows("group_settings", "conversation_id", id)).isEqualTo(1);
    }

    @Test
    void groupAddNotAllowedListsThePeopleAndCreatesNothing() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Group");
        UUID bob = USER_SERVICE.addUser("Bob Group");
        UUID private1 = USER_SERVICE.addUser("Private Group");
        UUID blocker = USER_SERVICE.addUser("Blocker Group");
        UUID unknown = UUID.randomUUID();
        USER_SERVICE.refuseGroupAdd(private1);
        USER_SERVICE.block(blocker, alice);
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class);

        MvcResult refused = createGroup(alice, "Nope", bob, private1, blocker, unknown);

        assertError(refused, 403, "GROUP_ADD_NOT_ALLOWED");
        assertThat(ids(refused, "$.data.userIds[*]"))
                .containsExactly(private1.toString(), blocker.toString(), unknown.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class)).isEqualTo(before);
    }

    @Test
    void createValidatesNameMembersAndSize() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Group");
        UUID bob = USER_SERVICE.addUser("Bob Group");

        MvcResult noName = createGroup(alice, "   ", bob);
        assertStatus(noName, 400);
        assertThat(field(noName, "$.data.name")).isNotNull();

        MvcResult longName = createGroup(alice, "x".repeat(101), bob);
        assertStatus(longName, 400);

        MvcResult onlyMe = createGroup(alice, "Alone", alice);
        assertStatus(onlyMe, 400);
        assertThat(field(onlyMe, "$.data.memberIds")).isNotNull();

        UUID[] tooMany = new UUID[256];   // 256 others + me = 257 > 256
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = UUID.randomUUID();
        }
        assertError(createGroup(alice, "Huge", tooMany), 409, "GROUP_FULL");
    }

    // ----------------------------------------------------------------- members

    @Test
    void adminsAddAndRemoveMembersAndEveryRuleHasItsErrorCode() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Group");
        UUID bob = USER_SERVICE.addUser("Bob Group");
        UUID carol = USER_SERVICE.addUser("Carol Group");
        UUID dave = USER_SERVICE.addUser("Dave Group");
        UUID stranger = USER_SERVICE.addUser("Stranger Group");
        String id = groupId(alice, "Rules", bob);
        String members = "/api/conversations/" + id + "/members";

        assertError(postAs(bob, members, Map.of("userIds", List.of(carol.toString()))), 403, "NOT_GROUP_ADMIN");
        assertError(postAs(alice, members, Map.of("userIds", List.of(bob.toString(), alice.toString()))), 409, "ALREADY_MEMBER");
        assertStatus(postAs(stranger, members, Map.of("userIds", List.of(carol.toString()))), 404);

        MvcResult added = postAs(alice, members, Map.of("userIds", List.of(carol.toString(), dave.toString(), bob.toString())));
        assertStatus(added, 200);
        assertThat(ids(added, "$.data.participants[*].id")).containsExactlyInAnyOrder(
                alice.toString(), bob.toString(), carol.toString(), dave.toString());

        assertError(deleteAs(alice, members + "/" + alice), 400, "CANNOT_CHANGE_SELF");
        assertError(deleteAs(bob, members + "/" + carol), 403, "NOT_GROUP_ADMIN");
        assertStatus(deleteAs(alice, members + "/" + stranger), 404);

        MvcResult removed = deleteAs(alice, members + "/" + dave);
        assertStatus(removed, 200);
        assertThat(ids(removed, "$.data.participants[*].id")).doesNotContain(dave.toString());
        assertStatus(deleteAs(alice, members + "/" + dave), 404);   // not a member any more
    }

    @Test
    void groupEndpointsOnAPrivateChatAreNotAGroup() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Group");
        UUID bob = USER_SERVICE.addUser("Bob Group");
        String id = field(startConversation(alice, bob), "$.data.id");

        assertError(postAs(alice, "/api/conversations/" + id + "/leave", null), 400, "NOT_A_GROUP");
        assertError(patchAs(alice, "/api/conversations/" + id + "/group", Map.of("name", "x")), 400, "NOT_A_GROUP");
        assertError(postAs(alice, "/api/conversations/" + id + "/members", Map.of("userIds", List.of(UUID.randomUUID().toString()))),
                400, "NOT_A_GROUP");
    }

    @Test
    void aRemovedMemberKeepsTheChatReadOnlyAndSeesNobodyInIt() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Group");
        UUID bob = USER_SERVICE.addUser("Bob Group");
        UUID carol = USER_SERVICE.addUser("Carol Group");
        String id = groupId(alice, "Removed", bob, carol);

        assertStatus(deleteAs(alice, "/api/conversations/" + id + "/members/" + bob), 200);

        MvcResult bobsView = getAs(bob, "/api/conversations/" + id);
        assertStatus(bobsView, 200);
        assertThat(field(bobsView, "$.data.membership.active")).isEqualTo("false");
        assertThat(field(bobsView, "$.data.membership.leftAt")).isNotNull();
        assertThat(field(bobsView, "$.data.membership.role")).isEqualTo("MEMBER");
        assertThat(ids(bobsView, "$.data.participants[*]")).isEmpty();
        assertThat(ids(bobsView, "$.data.group.adminIds[*]")).isEmpty();
        assertThat(field(bobsView, "$.data.group.name")).isEqualTo("Removed");
        assertThat(getAs(bob, "/api/conversations").getResponse().getContentAsString()).contains(id);

        // everything that changes the group is closed for him now
        assertError(postAs(bob, "/api/conversations/" + id + "/leave", null), 403, "NOT_A_MEMBER");
        assertError(patchAs(bob, "/api/conversations/" + id + "/group", Map.of("name", "Mine now")), 403, "NOT_A_MEMBER");
        assertError(postAs(bob, "/api/conversations/" + id + "/members", Map.of("userIds", List.of(carol.toString()))),
                403, "NOT_A_MEMBER");

        // other services see that he is gone and when he left
        MvcResult internal = internal(id);
        assertThat(ids(internal, "$.data.participantIds[*]")).containsExactlyInAnyOrder(alice.toString(), carol.toString());
        List<Object> bobsLeftAt = JsonPath.read(internal.getResponse().getContentAsString(),
                "$.data.members[?(@.userId == '" + bob + "')].leftAt");
        assertThat(bobsLeftAt).hasSize(1).doesNotContainNull();
    }

    @Test
    void addingSomebodyAgainStartsASecondMembershipPeriod() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Group");
        UUID bob = USER_SERVICE.addUser("Bob Group");
        String id = groupId(alice, "Again", bob);

        assertStatus(deleteAs(alice, "/api/conversations/" + id + "/members/" + bob), 200);
        assertStatus(postAs(alice, "/api/conversations/" + id + "/members", Map.of("userIds", List.of(bob.toString()))), 200);

        MvcResult internal = internal(id);
        List<Map<String, Object>> bobsPeriods = JsonPath.read(internal.getResponse().getContentAsString(),
                "$.data.members[?(@.userId == '" + bob + "')]");
        assertThat(bobsPeriods).hasSize(2);
        assertThat(bobsPeriods.get(0).get("leftAt")).isNotNull();
        assertThat(bobsPeriods.get(1).get("leftAt")).isNull();
        assertThat(Instant.parse(bobsPeriods.get(1).get("joinedAt").toString()))
                .isAfter(Instant.parse(bobsPeriods.get(0).get("leftAt").toString()));
        assertThat(ids(internal, "$.data.participantIds[*]")).contains(bob.toString());

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM conversation_participants WHERE conversation_id = ? AND user_id = ?",
                Integer.class, id, bob.toString());
        assertThat(rows).isEqualTo(1);
        MvcResult bobsView = getAs(bob, "/api/conversations/" + id);
        assertThat(field(bobsView, "$.data.membership.active")).isEqualTo("true");
        assertThat(field(bobsView, "$.data.membership.role")).isEqualTo("MEMBER");
    }

    @Test
    void twoAdminsAddingTheSamePersonAtOnceAddThemOnce() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Group");
        UUID bob = USER_SERVICE.addUser("Bob Group");
        UUID dave = USER_SERVICE.addUser("Dave Group");
        String id = groupId(alice, "Race", bob);
        assertStatus(putAs(alice, "/api/conversations/" + id + "/members/" + bob + "/role", Map.of("role", "ADMIN")), 200);

        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            UUID admin = i % 2 == 0 ? alice : bob;
            Callable<MvcResult> call = () -> {
                go.await();
                return postAs(admin, "/api/conversations/" + id + "/members", Map.of("userIds", List.of(dave.toString())));
            };
            futures.add(pool.submit(call));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            statuses.add(future.get().getResponse().getStatus());
        }
        pool.shutdown();

        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        Integer activeRows = jdbc.queryForObject("SELECT COUNT(*) FROM conversation_participants "
                + "WHERE conversation_id = ? AND user_id = ? AND active = TRUE", Integer.class, id, dave.toString());
        Integer openPeriods = jdbc.queryForObject("SELECT COUNT(*) FROM membership_periods "
                + "WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL", Integer.class, id, dave.toString());
        assertThat(activeRows).isEqualTo(1);
        assertThat(openPeriods).isEqualTo(1);
    }

    // ------------------------------------------------------------- roles, leave

    @Test
    void adminsMakeAndDismissAdmins() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Group");
        UUID bob = USER_SERVICE.addUser("Bob Group");
        String id = groupId(alice, "Roles", bob);
        String bobsRole = "/api/conversations/" + id + "/members/" + bob + "/role";

        assertError(putAs(bob, "/api/conversations/" + id + "/members/" + alice + "/role", Map.of("role", "MEMBER")),
                403, "NOT_GROUP_ADMIN");
        assertError(putAs(alice, "/api/conversations/" + id + "/members/" + alice + "/role", Map.of("role", "MEMBER")),
                400, "CANNOT_CHANGE_SELF");

        MvcResult promoted = putAs(alice, bobsRole, Map.of("role", "ADMIN"));
        assertStatus(promoted, 200);
        assertThat(ids(promoted, "$.data.group.adminIds[*]")).containsExactlyInAnyOrder(alice.toString(), bob.toString());
        assertThat(field(getAs(bob, "/api/conversations/" + id), "$.data.membership.role")).isEqualTo("ADMIN");

        MvcResult dismissed = putAs(bob, "/api/conversations/" + id + "/members/" + alice + "/role", Map.of("role", "MEMBER"));
        assertStatus(dismissed, 200);
        assertThat(ids(dismissed, "$.data.group.adminIds[*]")).containsExactly(bob.toString());
    }

    @Test
    void whenTheLastAdminLeavesTheEarliestMemberBecomesAdmin() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Group");
        UUID bob = USER_SERVICE.addUser("Bob Group");
        UUID carol = USER_SERVICE.addUser("Carol Group");
        String id = groupId(alice, "Leaving", bob);
        // Carol joins later than Bob, so Bob must become the admin
        assertStatus(postAs(alice, "/api/conversations/" + id + "/members", Map.of("userIds", List.of(carol.toString()))), 200);

        MvcResult left = postAs(alice, "/api/conversations/" + id + "/leave", null);

        assertStatus(left, 200);
        assertThat(field(left, "$.data.membership.active")).isEqualTo("false");
        assertThat(ids(left, "$.data.participants[*]")).isEmpty();
        MvcResult bobsView = getAs(bob, "/api/conversations/" + id);
        assertThat(field(bobsView, "$.data.membership.role")).isEqualTo("ADMIN");
        assertThat(ids(bobsView, "$.data.group.adminIds[*]")).containsExactly(bob.toString());
        assertThat(ids(bobsView, "$.data.participants[*].id")).containsExactlyInAnyOrder(bob.toString(), carol.toString());
        assertThat(field(getAs(carol, "/api/conversations/" + id), "$.data.membership.role")).isEqualTo("MEMBER");
    }

    // --------------------------------------------------------- name, settings

    @Test
    void nameDescriptionAndSettingsFollowTheAdminRules() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Group");
        UUID bob = USER_SERVICE.addUser("Bob Group");
        String id = groupId(alice, "Settings", bob);
        String group = "/api/conversations/" + id + "/group";

        // a member may rename while onlyAdminsCanEdit is off, but never change a setting
        MvcResult renamed = patchAs(bob, group, Map.of("name", "Renamed by Bob", "description", "Hello"));
        assertStatus(renamed, 200);
        assertThat(field(renamed, "$.data.group.name")).isEqualTo("Renamed by Bob");
        assertThat(field(renamed, "$.data.group.description")).isEqualTo("Hello");
        assertError(patchAs(bob, group, Map.of("onlyAdminsCanSend", true)), 403, "NOT_GROUP_ADMIN");

        MvcResult locked = patchAs(alice, group, Map.of("onlyAdminsCanSend", true, "onlyAdminsCanEdit", true, "description", ""));
        assertStatus(locked, 200);
        assertThat(field(locked, "$.data.group.onlyAdminsCanSend")).isEqualTo("true");
        assertThat(field(locked, "$.data.group.onlyAdminsCanEdit")).isEqualTo("true");
        assertThat(field(locked, "$.data.group.description")).isNull();
        assertError(patchAs(bob, group, Map.of("name", "Bob again")), 403, "NOT_GROUP_ADMIN");

        assertThat(field(internal(id), "$.data.onlyAdminsCanSend")).isEqualTo("true");
        assertStatus(patchAs(alice, group, Map.of()), 400);
        MvcResult badName = patchAs(alice, group, Map.of("name", " "));
        assertStatus(badName, 400);
        assertThat(field(badName, "$.data.name")).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult internal(String conversationId) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/internal/conversations/" + conversationId)
                .header("X-Internal-Token", FakeUserService.TOKEN)).andReturn();
    }

    private static List<String> ids(MvcResult result, String jsonPath) throws Exception {
        List<Object> values = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        return values.stream().map(String::valueOf).toList();
    }
}
