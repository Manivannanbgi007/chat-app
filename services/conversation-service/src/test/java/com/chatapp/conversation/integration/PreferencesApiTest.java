package com.chatapp.conversation.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.conversation.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Mute, archive and pin: my own settings per chat, on a real MySQL. */
class PreferencesApiTest extends AbstractIntegrationTest {

    @Test
    void muteForAWhileOrAlwaysAndUnmute() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Prefs");
        UUID bob = USER_SERVICE.addUser("Bob Prefs");
        String id = field(startConversation(alice, bob), "$.data.id");
        String mute = "/api/conversations/" + id + "/mute";

        MvcResult week = putAs(alice, mute, Map.of("duration", "ONE_WEEK"));
        assertStatus(week, 200);
        Instant until = Instant.parse(field(week, "$.data.preferences.mutedUntil"));
        assertThat(until).isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(60),
                Instant.now().plus(Duration.ofDays(7)).plusSeconds(60));

        assertThat(field(putAs(alice, mute, Map.of("duration", "ALWAYS")), "$.data.preferences.mutedUntil"))
                .isEqualTo("9999-12-31T23:59:59Z");
        // only MY setting: Bob's chat is not muted
        assertThat(field(getAs(bob, "/api/conversations/" + id), "$.data.preferences.mutedUntil")).isNull();

        assertThat(field(deleteAs(alice, mute), "$.data.preferences.mutedUntil")).isNull();
        assertStatus(putAs(alice, mute, Map.of("duration", "FOREVER")), 400);
    }

    @Test
    void archiveAndPinExcludeEachOtherAndAtMostThreeArePinned() throws Exception {
        UUID me = USER_SERVICE.addUser("Me Prefs");
        String[] chats = new String[4];
        for (int i = 0; i < chats.length; i++) {
            chats[i] = field(startConversation(me, USER_SERVICE.addUser("Friend Prefs " + i)), "$.data.id");
        }

        for (int i = 0; i < 3; i++) {
            assertThat(field(putAs(me, "/api/conversations/" + chats[i] + "/pin", null), "$.data.preferences.pinned"))
                    .isEqualTo("true");
        }
        assertError(putAs(me, "/api/conversations/" + chats[3] + "/pin", null), 409, "PIN_LIMIT_REACHED");
        assertStatus(putAs(me, "/api/conversations/" + chats[0] + "/pin", null), 200);   // pinning again is fine

        // archiving a pinned chat unpins it, which makes room for the fourth
        MvcResult archived = putAs(me, "/api/conversations/" + chats[0] + "/archive", null);
        assertThat(field(archived, "$.data.preferences.archived")).isEqualTo("true");
        assertThat(field(archived, "$.data.preferences.pinned")).isEqualTo("false");
        assertStatus(putAs(me, "/api/conversations/" + chats[3] + "/pin", null), 200);

        // pinning an archived chat takes it out of the archive (after unpinning another one)
        assertStatus(deleteAs(me, "/api/conversations/" + chats[1] + "/pin"), 200);
        MvcResult pinnedAgain = putAs(me, "/api/conversations/" + chats[0] + "/pin", null);
        assertThat(field(pinnedAgain, "$.data.preferences.pinned")).isEqualTo("true");
        assertThat(field(pinnedAgain, "$.data.preferences.archived")).isEqualTo("false");

        MvcResult list = getAs(me, "/api/conversations");
        List<Boolean> pinned = JsonPath.read(list.getResponse().getContentAsString(), "$.data[*].preferences.pinned");
        assertThat(pinned.stream().filter(Boolean::booleanValue)).hasSize(3);
    }

    @Test
    void archivedChatsAndGroupsILeftStayInTheList() throws Exception {
        UUID me = USER_SERVICE.addUser("Me Prefs");
        UUID bob = USER_SERVICE.addUser("Bob Prefs");
        String chat = field(startConversation(me, bob), "$.data.id");
        String group = groupId(me, "Left group", bob);
        assertStatus(putAs(me, "/api/conversations/" + chat + "/archive", null), 200);
        assertStatus(postAs(me, "/api/conversations/" + group + "/leave", null), 200);

        // a group I left can still be archived and unarchived
        assertThat(field(putAs(me, "/api/conversations/" + group + "/archive", null), "$.data.preferences.archived"))
                .isEqualTo("true");
        assertThat(field(deleteAs(me, "/api/conversations/" + group + "/archive"), "$.data.preferences.archived"))
                .isEqualTo("false");

        String list = getAs(me, "/api/conversations").getResponse().getContentAsString();
        assertThat(list).contains(chat).contains(group);
    }

    @Test
    void settingsOfAChatIWasNeverInAre404() throws Exception {
        UUID alice = USER_SERVICE.addUser("Alice Prefs");
        UUID bob = USER_SERVICE.addUser("Bob Prefs");
        UUID stranger = USER_SERVICE.addUser("Stranger Prefs");
        String id = field(startConversation(alice, bob), "$.data.id");

        assertStatus(putAs(stranger, "/api/conversations/" + id + "/pin", null), 404);
        assertStatus(putAs(stranger, "/api/conversations/" + id + "/mute", Map.of("duration", "ALWAYS")), 404);
        assertStatus(deleteAs(stranger, "/api/conversations/" + id + "/archive"), 404);
        assertStatus(putAs(alice, "/api/conversations/" + UUID.randomUUID() + "/archive", null), 404);
    }
}
