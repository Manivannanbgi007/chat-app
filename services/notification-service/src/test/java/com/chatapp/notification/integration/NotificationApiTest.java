package com.chatapp.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** The REST API: what a logged-in user sees and changes. */
class NotificationApiTest extends AbstractIntegrationTest {

    private UUID me;
    private UUID other;

    @BeforeEach
    void setUp() {
        me = newId();
        other = newId();
    }

    // ------------------------------------------------------------------ security

    @Test
    void everyEndpointNeedsALogin() throws Exception {
        UUID id = newId();
        assertThat(mockMvc.perform(get("/api/notifications")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/notifications/unread-count")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(put("/api/notifications/" + id + "/read")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(put("/api/notifications/read-all")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/notifications/preferences")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post("/api/notifications/device-tokens").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(delete("/api/notifications/device-tokens?token=x")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void aGarbageTokenIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer not-a-jwt")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    // -------------------------------------------------------------------- list

    @Test
    void theListIsPagedNewestActivityFirst() throws Exception {
        UUID oldest = insertNotification(me, "GROUP_ADDED", "A", "x", true, 300);
        UUID middle = insertNotification(me, "GROUP_ADMIN", "B", "x", false, 200);
        UUID newest = insertNotification(me, "GROUP_REMOVED", "C", "x", false, 100);
        insertNotification(other, "GROUP_ADDED", "not mine", "x", false, 50);

        MvcResult first = getAs(me, "/api/notifications?page=0&size=2");
        assertStatus(first, 200);
        assertThat(field(first, "$.data.items[0].id")).isEqualTo(newest.toString());
        assertThat(field(first, "$.data.items[1].id")).isEqualTo(middle.toString());
        assertThat(field(first, "$.data.totalItems")).isEqualTo("3");
        assertThat(field(first, "$.data.hasMore")).isEqualTo("true");
        assertThat(field(first, "$.data.page")).isEqualTo("0");
        assertThat(field(first, "$.data.size")).isEqualTo("2");

        MvcResult second = getAs(me, "/api/notifications?page=1&size=2");
        assertThat(field(second, "$.data.items[0].id")).isEqualTo(oldest.toString());
        assertThat(field(second, "$.data.hasMore")).isEqualTo("false");
        assertThat(this.<List<Object>>read(second, "$.data.items")).hasSize(1);
    }

    @Test
    void theListShowsTheFieldsTheFrontendNeeds() throws Exception {
        insertNotification(me, "GROUP_ADDED", "Trip", "Asha added you", false, 10);
        MvcResult result = getAs(me, "/api/notifications");
        assertThat(field(result, "$.data.items[0].type")).isEqualTo("GROUP_ADDED");
        assertThat(field(result, "$.data.items[0].title")).isEqualTo("Trip");
        assertThat(field(result, "$.data.items[0].body")).isEqualTo("Asha added you");
        assertThat(field(result, "$.data.items[0].count")).isEqualTo("1");
        assertThat(field(result, "$.data.items[0].read")).isEqualTo("false");
        assertThat(field(result, "$.data.items[0].createdAt")).isNotBlank();
        assertThat(field(result, "$.data.items[0].updatedAt")).isNotBlank();
    }

    @Test
    void anEmptyListIsNotAnError() throws Exception {
        MvcResult result = getAs(me, "/api/notifications");
        assertStatus(result, 200);
        assertThat(this.<List<Object>>read(result, "$.data.items")).isEmpty();
        assertThat(field(result, "$.data.totalItems")).isEqualTo("0");
    }

    @Test
    void badPagingIsA400() throws Exception {
        assertStatus(getAs(me, "/api/notifications?size=0"), 400);
        assertStatus(getAs(me, "/api/notifications?size=51"), 400);
        assertStatus(getAs(me, "/api/notifications?page=-1"), 400);
        assertStatus(getAs(me, "/api/notifications?page=abc"), 400);
    }

    // ------------------------------------------------------------ unread / read

    @Test
    void unreadCountCountsOnlyMyUnreadNotifications() throws Exception {
        insertNotification(me, "GROUP_ADDED", "A", "x", false, 30);
        insertNotification(me, "GROUP_ADMIN", "B", "x", false, 20);
        insertNotification(me, "GROUP_REMOVED", "C", "x", true, 10);
        insertNotification(other, "GROUP_ADDED", "D", "x", false, 10);

        assertThat(field(getAs(me, "/api/notifications/unread-count"), "$.data.unreadCount")).isEqualTo("2");
        assertThat(field(getAs(newId(), "/api/notifications/unread-count"), "$.data.unreadCount")).isEqualTo("0");
    }

    @Test
    void markingOneReadWorksAndIsRepeatable() throws Exception {
        UUID id = insertNotification(me, "GROUP_ADDED", "A", "x", false, 10);

        MvcResult result = putAs(me, "/api/notifications/" + id + "/read", null);
        assertStatus(result, 200);
        assertThat(field(result, "$.data.read")).isEqualTo("true");
        assertThat(field(getAs(me, "/api/notifications/unread-count"), "$.data.unreadCount")).isEqualTo("0");
        assertThat(jdbc.queryForObject("SELECT unread_key FROM notifications WHERE id = ?", String.class, id.toString())).isNull();

        assertStatus(putAs(me, "/api/notifications/" + id + "/read", null), 200);
    }

    @Test
    void somebodyElsesNotificationLooksLikeItDoesNotExist() throws Exception {
        UUID id = insertNotification(other, "GROUP_ADDED", "A", "x", false, 10);

        assertStatus(putAs(me, "/api/notifications/" + id + "/read", null), 404);
        assertStatus(putAs(me, "/api/notifications/" + newId() + "/read", null), 404);
        assertThat(jdbc.queryForObject("SELECT is_read FROM notifications WHERE id = ?", Boolean.class, id.toString())).isFalse();
    }

    @Test
    void anIdThatIsNotAUuidIsA400() throws Exception {
        assertStatus(putAs(me, "/api/notifications/not-a-uuid/read", null), 400);
    }

    @Test
    void readAllMarksOnlyMyUnreadOnes() throws Exception {
        insertNotification(me, "GROUP_ADDED", "A", "x", false, 30);
        insertNotification(me, "GROUP_ADMIN", "B", "x", false, 20);
        insertNotification(me, "GROUP_REMOVED", "C", "x", true, 10);
        insertNotification(other, "GROUP_ADDED", "D", "x", false, 10);

        MvcResult result = putAs(me, "/api/notifications/read-all", null);
        assertStatus(result, 200);
        assertThat(field(result, "$.data.updated")).isEqualTo("2");
        assertThat(field(getAs(me, "/api/notifications/unread-count"), "$.data.unreadCount")).isEqualTo("0");
        assertThat(field(getAs(other, "/api/notifications/unread-count"), "$.data.unreadCount")).isEqualTo("1");
        assertThat(field(putAs(me, "/api/notifications/read-all", null), "$.data.updated")).isEqualTo("0");
    }

    // ------------------------------------------------------------ preferences

    @Test
    void preferencesStartWithEverythingOnAndCanBeChanged() throws Exception {
        MvcResult defaults = getAs(me, "/api/notifications/preferences");
        assertStatus(defaults, 200);
        for (String name : List.of("pushEnabled", "messagesEnabled", "groupsEnabled", "callsEnabled")) {
            assertThat(field(defaults, "$.data." + name)).isEqualTo("true");
        }

        MvcResult updated = putAs(me, "/api/notifications/preferences",
                Map.of("pushEnabled", false, "messagesEnabled", true, "groupsEnabled", false, "callsEnabled", true));
        assertStatus(updated, 200);
        assertThat(field(updated, "$.data.pushEnabled")).isEqualTo("false");

        MvcResult again = getAs(me, "/api/notifications/preferences");
        assertThat(field(again, "$.data.pushEnabled")).isEqualTo("false");
        assertThat(field(again, "$.data.messagesEnabled")).isEqualTo("true");
        assertThat(field(again, "$.data.groupsEnabled")).isEqualTo("false");
        assertThat(field(getAs(other, "/api/notifications/preferences"), "$.data.pushEnabled")).as("other users are unaffected").isEqualTo("true");
    }

    @Test
    void preferencesNeedAllFourSwitches() throws Exception {
        assertStatus(putAs(me, "/api/notifications/preferences", Map.of("pushEnabled", false)), 400);
        assertStatus(putAs(me, "/api/notifications/preferences", Map.of()), 400);
    }

    // ----------------------------------------------------------- device tokens

    @Test
    void aTokenCanBeRegisteredTwiceWithoutDuplicates() throws Exception {
        String token = newToken();
        registerDevice(me, token);
        registerDevice(me, token);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", Integer.class, me.toString())).isEqualTo(1);
    }

    @Test
    void aTokenMovesToTheLatestUserWhoRegistersIt() throws Exception {
        String token = newToken();
        registerDevice(me, token);
        registerDevice(other, token);        // for example somebody else logged in on the same browser
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", Integer.class, me.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", Integer.class, other.toString())).isEqualTo(1);
    }

    @Test
    void removingATokenRemovesOnlyMyOwn() throws Exception {
        String mine = newToken();
        String theirs = newToken();
        registerDevice(me, mine);
        registerDevice(other, theirs);

        assertStatus(deleteAs(me, "/api/notifications/device-tokens?token=" + theirs), 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", Integer.class, other.toString())).isEqualTo(1);

        assertStatus(deleteAs(me, "/api/notifications/device-tokens?token=" + mine), 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", Integer.class, me.toString())).isZero();
        assertStatus(deleteAs(me, "/api/notifications/device-tokens?token=" + mine), 200);       // again: still fine
    }

    @Test
    void badTokenRequestsAreA400() throws Exception {
        assertStatus(postAs(me, "/api/notifications/device-tokens", Map.of("token", "", "platform", "WEB")), 400);
        assertStatus(postAs(me, "/api/notifications/device-tokens", Map.of("token", "   ", "platform", "WEB")), 400);
        assertStatus(postAs(me, "/api/notifications/device-tokens", Map.of("token", "short", "platform", "WEB")), 400);
        assertStatus(postAs(me, "/api/notifications/device-tokens", Map.of("token", "x".repeat(4097), "platform", "WEB")), 400);
        assertStatus(postAs(me, "/api/notifications/device-tokens", Map.of("token", newToken(), "platform", "TOASTER")), 400);
        assertStatus(postAs(me, "/api/notifications/device-tokens", Map.of("token", newToken())), 400);
        assertStatus(deleteAs(me, "/api/notifications/device-tokens?token="), 400);
        assertStatus(deleteAs(me, "/api/notifications/device-tokens"), 400);
    }

    @Test
    void aLongTokenIsAccepted() throws Exception {
        assertStatus(postAs(me, "/api/notifications/device-tokens", Map.of("token", "t".repeat(4096), "platform", "ANDROID")), 200);
        assertStatus(deleteAs(me, "/api/notifications/device-tokens?token=" + "t".repeat(4096)), 200);
    }
}
