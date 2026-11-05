package com.chatapp.status.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.status.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Reading and changing my status privacy. */
class PrivacyApiTest extends AbstractIntegrationTest {

    @Test
    void theDefaultIsAllContacts() throws Exception {
        MvcResult result = get("/api/statuses/privacy", newPerson("New"));
        assertStatus(result, 200);
        assertThat(field(result, "$.data.mode")).isEqualTo("CONTACTS");
        assertThat((List<?>) read(result, "$.data.userIds")).isEmpty();
    }

    @Test
    void modeAndPeopleAreSavedTogetherAndReplacedTogether() throws Exception {
        Person me = newPerson("Me");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        MvcResult saved = put("/api/statuses/privacy", Map.of("mode", "CONTACTS_EXCEPT", "userIds", List.of(a, b, a)), me);
        assertStatus(saved, 200);
        assertThat(field(saved, "$.data.mode")).isEqualTo("CONTACTS_EXCEPT");
        List<String> ids = read(saved, "$.data.userIds");
        assertThat(ids).containsExactlyInAnyOrder(a.toString(), b.toString());      // the duplicate is kept once
        assertThat(field(get("/api/statuses/privacy", me), "$.data.mode")).isEqualTo("CONTACTS_EXCEPT");

        MvcResult replaced = put("/api/statuses/privacy", Map.of("mode", "ONLY_SHARE_WITH", "userIds", List.of(b)), me);
        List<String> after = read(replaced, "$.data.userIds");
        assertThat(after).containsExactly(b.toString());
        assertThat(rows("SELECT COUNT(*) FROM status_privacy_members WHERE owner_id = ?", me.id().toString())).isEqualTo(1);

        MvcResult back = put("/api/statuses/privacy", Map.of("mode", "CONTACTS", "userIds", List.of()), me);
        assertThat(field(back, "$.data.mode")).isEqualTo("CONTACTS");
        assertThat((List<?>) read(get("/api/statuses/privacy", me), "$.data.userIds")).isEmpty();
        assertThat(rows("SELECT COUNT(*) FROM status_privacy_members WHERE owner_id = ?", me.id().toString())).isZero();
    }

    @Test
    void contactsModeTakesNoPeopleAndNobodyListsThemselves() throws Exception {
        Person me = newPerson("Me");

        MvcResult withPeople = put("/api/statuses/privacy", Map.of("mode", "CONTACTS", "userIds", List.of(UUID.randomUUID())), me);
        assertStatus(withPeople, 400);
        assertThat(field(withPeople, "$.data.userIds")).isNotNull();

        MvcResult self = put("/api/statuses/privacy", Map.of("mode", "ONLY_SHARE_WITH", "userIds", List.of(me.id())), me);
        assertStatus(self, 400);
        assertThat(field(self, "$.data.userIds")).isNotNull();

        MvcResult noMode = put("/api/statuses/privacy", Map.of("userIds", List.of()), me);
        assertStatus(noMode, 400);

        // nothing was saved by the failed requests
        assertThat(field(get("/api/statuses/privacy", me), "$.data.mode")).isEqualTo("CONTACTS");
    }

    @Test
    void aMissingUserIdsListMeansNobody() throws Exception {
        Person me = newPerson("Me");
        MvcResult result = put("/api/statuses/privacy", Map.of("mode", "ONLY_SHARE_WITH"), me);
        assertStatus(result, 200);
        assertThat((List<?>) read(result, "$.data.userIds")).isEmpty();
    }
}
