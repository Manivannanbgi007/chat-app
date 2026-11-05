package com.chatapp.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.chatapp.user.TestJwt;
import com.chatapp.user.service.UserRegistrationService;
import com.chatapp.user.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end through HTTP, security, services, JPA and a real MySQL, using real signed tokens.
 * Users are created with the same service the Kafka consumer uses.
 */
class UserApiFlowTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRegistrationService registrationService;

    record TestUser(UUID id, String username, String phone) {
        String bearer() {
            return "Bearer " + TestJwt.token(id);
        }
    }

    // ---- profile ----

    @Test
    void protectedEndpointsRejectRequestsWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/contacts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/search").param("q", "ab")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void userCanReadAndUpdateTheirOwnProfile() throws Exception {
        TestUser alice = newUser("alice");

        call(alice, get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(alice.id().toString()))
                .andExpect(jsonPath("$.data.username").value(alice.username()))
                .andExpect(jsonPath("$.data.email").value(alice.username() + "@example.com"))
                .andExpect(jsonPath("$.data.phone").value(alice.phone()))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"));

        call(alice, put("/api/users/me"), "{\"displayName\":\"  Alice Updated \",\"about\":\"On holiday\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Alice Updated"))
                .andExpect(jsonPath("$.data.about").value("On holiday"));

        call(alice, get("/api/users/me"))
                .andExpect(jsonPath("$.data.displayName").value("Alice Updated"))
                .andExpect(jsonPath("$.data.about").value("On holiday"));

        // clearing about
        call(alice, put("/api/users/me"), "{\"displayName\":\"Alice Updated\",\"about\":\"\"}")
                .andExpect(jsonPath("$.data.about").doesNotExist());
    }

    @Test
    void validationErrorsAreReturnedPerField() throws Exception {
        TestUser alice = newUser("val");

        call(alice, put("/api/users/me"), "{\"displayName\":\"\",\"about\":\"" + "x".repeat(140) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.displayName").exists())
                .andExpect(jsonPath("$.data.about").exists());
    }

    @Test
    void profileOfAnUnknownUserIs404() throws Exception {
        TestUser alice = newUser("unk");

        call(alice, get("/api/users/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void publicProfileNeverContainsEmailOrPhone() throws Exception {
        TestUser alice = newUser("pub");
        TestUser bob = newUser("pub");

        call(alice, get("/api/users/" + bob.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(bob.username()))
                .andExpect(jsonPath("$.data.email").doesNotExist())
                .andExpect(jsonPath("$.data.phone").doesNotExist());
    }

    // ---- search ----

    @Test
    void searchFindsByUsernamePrefixNameAndExactPhoneButNotMe() throws Exception {
        String tag = "sx" + shortId();
        TestUser me = newUser(tag + "me");
        TestUser bob = newUser(tag + "bob");

        // username prefix (also matches me, but I must not be in my own results)
        List<String> ids = searchIds(me, tag);
        assertThat(ids).contains(bob.id().toString()).doesNotContain(me.id().toString());

        // display name contains: display name is the uppercased username in newUser()
        assertThat(searchIds(me, tag.toUpperCase().substring(1))).contains(bob.id().toString());

        // exact phone
        assertThat(searchIds(me, bob.phone())).containsExactly(bob.id().toString());
        // partial phone does not match
        assertThat(searchIds(me, bob.phone().substring(0, bob.phone().length() - 2))).isEmpty();
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainCharacters() throws Exception {
        TestUser me = newUser("wild");
        newUser("wild");

        assertThat(searchIds(me, "%%")).isEmpty();
        assertThat(searchIds(me, "__")).isEmpty();
    }

    @Test
    void searchTextLengthIsChecked() throws Exception {
        TestUser me = newUser("len");

        call(me, get("/api/users/search").param("q", "a")).andExpect(status().isBadRequest());
        call(me, get("/api/users/search").param("q", "x".repeat(51))).andExpect(status().isBadRequest());
    }

    @Test
    void searchHidesUsersWhoBlockedMe() throws Exception {
        String tag = "sb" + shortId();
        TestUser me = newUser(tag + "me");
        TestUser grumpy = newUser(tag + "grumpy");
        TestUser friendly = newUser(tag + "friendly");

        call(grumpy, post("/api/users/" + me.id() + "/block")).andExpect(status().isOk());

        List<String> ids = searchIds(me, tag);
        assertThat(ids).contains(friendly.id().toString()).doesNotContain(grumpy.id().toString());
    }

    // ---- contacts ----

    @Test
    void contactLifecycle() throws Exception {
        TestUser alice = newUser("ca");
        TestUser bob = newUser("cb");

        String contactId = JsonPath.read(
                call(alice, post("/api/contacts"), "{\"contactUserId\":\"" + bob.id() + "\",\"nickname\":\" Bobby \"}")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.contactUserId").value(bob.id().toString()))
                        .andExpect(jsonPath("$.data.nickname").value("Bobby"))
                        .andExpect(jsonPath("$.data.user.username").value(bob.username()))
                        .andReturn().getResponse().getContentAsString(),
                "$.data.id");

        // adding twice
        call(alice, post("/api/contacts"), "{\"contactUserId\":\"" + bob.id() + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("ALREADY_CONTACT"));

        // list
        call(alice, get("/api/contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(contactId));

        // bob cannot delete alice's contact, and it still exists afterwards
        call(bob, delete("/api/contacts/" + contactId)).andExpect(status().isNotFound());
        call(alice, get("/api/contacts")).andExpect(jsonPath("$.data.length()").value(1));

        // alice can
        call(alice, delete("/api/contacts/" + contactId)).andExpect(status().isOk());
        call(alice, get("/api/contacts")).andExpect(jsonPath("$.data.length()").value(0));
        call(alice, delete("/api/contacts/" + contactId)).andExpect(status().isNotFound());
    }

    @Test
    void contactRulesAreEnforced() throws Exception {
        TestUser alice = newUser("cr");

        // yourself
        call(alice, post("/api/contacts"), "{\"contactUserId\":\"" + alice.id() + "\"}")
                .andExpect(status().isBadRequest());
        // unknown user
        call(alice, post("/api/contacts"), "{\"contactUserId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isNotFound());
        // missing id
        call(alice, post("/api/contacts"), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.contactUserId").exists());
    }

    @Test
    void cannotAddSomeoneWhoBlockedYou() throws Exception {
        TestUser alice = newUser("cn");
        TestUser grumpy = newUser("cn");
        call(grumpy, post("/api/users/" + alice.id() + "/block")).andExpect(status().isOk());

        call(alice, post("/api/contacts"), "{\"contactUserId\":\"" + grumpy.id() + "\"}")
                .andExpect(status().isNotFound());
    }

    // ---- blocking ----

    @Test
    void blockingHidesTheBlockerAndIsReversible() throws Exception {
        TestUser alice = newUser("bk");
        TestUser bob = newUser("bk");

        // block is idempotent
        call(alice, post("/api/users/" + bob.id() + "/block")).andExpect(status().isOk());
        call(alice, post("/api/users/" + bob.id() + "/block")).andExpect(status().isOk());

        call(alice, get("/api/users/blocked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(bob.id().toString()));

        // bob can no longer see alice, as if she did not exist
        call(bob, get("/api/users/" + alice.id())).andExpect(status().isNotFound());
        // but alice still sees bob
        call(alice, get("/api/users/" + bob.id())).andExpect(status().isOk());

        // unblock (also idempotent)
        call(alice, delete("/api/users/" + bob.id() + "/block")).andExpect(status().isOk());
        call(alice, delete("/api/users/" + bob.id() + "/block")).andExpect(status().isOk());
        call(alice, get("/api/users/blocked")).andExpect(jsonPath("$.data.length()").value(0));
        call(bob, get("/api/users/" + alice.id())).andExpect(status().isOk());
    }

    @Test
    void blockRulesAreEnforced() throws Exception {
        TestUser alice = newUser("br");

        call(alice, post("/api/users/" + alice.id() + "/block")).andExpect(status().isBadRequest());
        call(alice, post("/api/users/" + UUID.randomUUID() + "/block")).andExpect(status().isNotFound());
    }

    // ---- privacy ----

    @Test
    void privacyDefaultsAndUpdate() throws Exception {
        TestUser alice = newUser("pd");

        call(alice, get("/api/users/me/privacy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastSeen").value("EVERYONE"))
                .andExpect(jsonPath("$.data.profilePhoto").value("EVERYONE"))
                .andExpect(jsonPath("$.data.about").value("EVERYONE"))
                .andExpect(jsonPath("$.data.groupAdd").value("EVERYONE"))
                .andExpect(jsonPath("$.data.readReceipts").value(true));

        call(alice, put("/api/users/me/privacy"),
                "{\"lastSeen\":\"NOBODY\",\"profilePhoto\":\"CONTACTS\",\"about\":\"CONTACTS\","
                        + "\"groupAdd\":\"CONTACTS\",\"readReceipts\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastSeen").value("NOBODY"));

        call(alice, get("/api/users/me/privacy"))
                .andExpect(jsonPath("$.data.lastSeen").value("NOBODY"))
                .andExpect(jsonPath("$.data.groupAdd").value("CONTACTS"))
                .andExpect(jsonPath("$.data.readReceipts").value(false));

        // invalid input
        call(alice, put("/api/users/me/privacy"), "{\"lastSeen\":\"WORLD\"}").andExpect(status().isBadRequest());
        call(alice, put("/api/users/me/privacy"), "{}").andExpect(status().isBadRequest());
    }

    @Test
    void aboutAndPhotoFollowThePrivacySettingsOfTheOwner() throws Exception {
        TestUser owner = newUser("po");
        TestUser viewer = newUser("pv");
        call(owner, put("/api/users/me"), "{\"displayName\":\"Owner\",\"about\":\"Secret about\"}")
                .andExpect(status().isOk());
        jdbc.update("UPDATE user_profiles SET profile_image_url = 'http://img/owner.png' WHERE user_id = ?",
                owner.id().toString());

        // everyone (default): visible
        call(viewer, get("/api/users/" + owner.id()))
                .andExpect(jsonPath("$.data.about").value("Secret about"))
                .andExpect(jsonPath("$.data.profileImageUrl").value("http://img/owner.png"));

        // contacts only: viewer is a stranger -> hidden
        call(owner, put("/api/users/me/privacy"),
                "{\"lastSeen\":\"EVERYONE\",\"profilePhoto\":\"CONTACTS\",\"about\":\"CONTACTS\","
                        + "\"groupAdd\":\"EVERYONE\",\"readReceipts\":true}").andExpect(status().isOk());
        call(viewer, get("/api/users/" + owner.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.about").doesNotExist())
                .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist())
                .andExpect(jsonPath("$.data.displayName").value("Owner"));

        // the viewer being in MY contacts does not help; the OWNER must have the viewer as contact
        call(viewer, post("/api/contacts"), "{\"contactUserId\":\"" + owner.id() + "\"}").andExpect(status().isCreated());
        call(viewer, get("/api/users/" + owner.id()))
                .andExpect(jsonPath("$.data.about").doesNotExist());

        // owner adds viewer to contacts -> visible
        call(owner, post("/api/contacts"), "{\"contactUserId\":\"" + viewer.id() + "\"}").andExpect(status().isCreated());
        call(viewer, get("/api/users/" + owner.id()))
                .andExpect(jsonPath("$.data.about").value("Secret about"))
                .andExpect(jsonPath("$.data.profileImageUrl").value("http://img/owner.png"));

        // nobody: hidden even for contacts
        call(owner, put("/api/users/me/privacy"),
                "{\"lastSeen\":\"EVERYONE\",\"profilePhoto\":\"NOBODY\",\"about\":\"NOBODY\","
                        + "\"groupAdd\":\"EVERYONE\",\"readReceipts\":true}").andExpect(status().isOk());
        call(viewer, get("/api/users/" + owner.id()))
                .andExpect(jsonPath("$.data.about").doesNotExist())
                .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist());

        // the owner always sees their own data
        call(owner, get("/api/users/" + owner.id()))
                .andExpect(jsonPath("$.data.about").value("Secret about"));
    }

    @Test
    void privacyAlsoApliesToSearchResults() throws Exception {
        String tag = "ps" + shortId();
        TestUser viewer = newUser(tag + "viewer");
        TestUser hidden = newUser(tag + "hidden");
        call(hidden, put("/api/users/me"), "{\"displayName\":\"Hidden\",\"about\":\"private text\"}");
        call(hidden, put("/api/users/me/privacy"),
                "{\"lastSeen\":\"EVERYONE\",\"profilePhoto\":\"NOBODY\",\"about\":\"NOBODY\","
                        + "\"groupAdd\":\"EVERYONE\",\"readReceipts\":true}");

        call(viewer, get("/api/users/search").param("q", tag + "hidden"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value(hidden.username()))
                .andExpect(jsonPath("$.data[0].about").doesNotExist());
    }

    // ---- helpers ----

    private TestUser newUser(String prefix) {
        UUID id = UUID.randomUUID();
        String username = prefix.toLowerCase() + shortId();
        // phone numbers are unique per user: +91 followed by 10 digits taken from the random id
        String phone = "+91" + String.format("%010d", Math.abs(id.getMostSignificantBits() % 10_000_000_000L));
        registrationService.createUser(UUID.randomUUID(), id, username, username + "@example.com", phone,
                username.toUpperCase());
        return new TestUser(id, username, phone);
    }

    private List<String> searchIds(TestUser caller, String q) throws Exception {
        String body = call(caller, get("/api/users/search").param("q", q))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data[*].id");
    }

    private ResultActions call(TestUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", user.bearer()));
    }

    private ResultActions call(TestUser user, MockHttpServletRequestBuilder request, String json) throws Exception {
        return mockMvc.perform(request.header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
