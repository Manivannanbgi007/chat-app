package com.chatapp.media.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;

import com.chatapp.media.support.AbstractIntegrationTest;
import com.chatapp.media.support.Samples;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Only the uploader may look at or delete an upload. For everybody else it simply does not exist. */
class OwnerAccessTest extends AbstractIntegrationTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void ownerGetsDetailsAndWorkingLinks() throws Exception {
        UUID id = uploadOk(owner, "photo.jpg", "image/jpeg", Samples.jpeg(800, 400), "IMAGE");

        MvcResult result = getAs(owner, "/api/media/" + id);

        assertStatus(result, 200);
        assertThat(field(result, "$.data.media.id")).isEqualTo(id.toString());
        assertThat(field(result, "$.data.media.hasThumbnail")).isEqualTo("true");
        assertThat(fetch(field(result, "$.data.url")).statusCode()).isEqualTo(200);
        assertThat(fetch(field(result, "$.data.thumbnailUrl")).statusCode()).isEqualTo(200);
        assertThat(java.time.Instant.parse(field(result, "$.data.expiresAt")))
                .isBetween(java.time.Instant.now().plusSeconds(200), java.time.Instant.now().plusSeconds(310));   // 5 minutes
        assertThat(countLogs(id, "VIEW_LINK")).isEqualTo(1);
    }

    @Test
    void linksPointToThePublicStorageAddressAndAreSigned() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(20, 20), "IMAGE");
        String url = field(getAs(owner, "/api/media/" + id), "$.data.url");

        assertThat(url).startsWith("http://").contains("/chat-media/").contains(objectKeyOf(id));
        assertThat(url).contains("X-Amz-Signature=").contains("X-Amz-Expires=300");
        assertThat(url).contains("response-content-type=image%2Fpng");
        // the same address without the signature is refused by the storage
        String unsigned = url.substring(0, url.indexOf('?'));
        assertThat(fetch(unsigned).statusCode()).isIn(400, 401, 403);
    }

    @Test
    void anotherUserGets404ForGetAndDelete() throws Exception {
        UUID id = uploadOk(owner, "secret.pdf", "application/pdf", Samples.pdf(), "DOCUMENT");

        MvcResult get = getAs(other, "/api/media/" + id);
        MvcResult delete = deleteAs(other, "/api/media/" + id);

        assertError(get, 404, "MEDIA_NOT_FOUND");
        assertError(delete, 404, "MEDIA_NOT_FOUND");
        assertThat(statusOf(id)).isEqualTo("UPLOADED");
        assertThat(objectExists(objectKeyOf(id))).isTrue();
        // the answer is the same as for an id that never existed
        assertThat(getAs(other, "/api/media/" + UUID.randomUUID()).getResponse().getContentAsString())
                .contains("Media not found");
    }

    @Test
    void unknownIdsAreNotFound() throws Exception {
        assertStatus(getAs(owner, "/api/media/" + UUID.randomUUID()), 404);
        assertStatus(deleteAs(owner, "/api/media/" + UUID.randomUUID()), 404);
    }

    @Test
    void requestsWithoutATokenAre401() throws Exception {
        UUID id = uploadOk(owner, "a.png", "image/png", Samples.png(5, 5), "IMAGE");
        assertStatus(mockMvc.perform(get("/api/media/" + id)).andReturn(), 401);
        assertStatus(mockMvc.perform(delete("/api/media/" + id)).andReturn(), 401);
    }

    @Test
    void deletingRemovesTheObjectsAndTheFileDisappears() throws Exception {
        UUID id = uploadOk(owner, "photo.jpg", "image/jpeg", Samples.jpeg(600, 600), "IMAGE");
        String key = objectKeyOf(id);
        String thumbnailKey = thumbnailKeyOf(id);
        String link = field(getAs(owner, "/api/media/" + id), "$.data.url");
        assertThat(fetch(link).statusCode()).isEqualTo(200);
        assertThat(objectExists(key)).isTrue();
        assertThat(objectExists(thumbnailKey)).isTrue();

        MvcResult result = deleteAs(owner, "/api/media/" + id);

        assertStatus(result, 200);
        assertThat(statusOf(id)).isEqualTo("DELETED");
        assertThat(objectExists(key)).isFalse();
        assertThat(objectExists(thumbnailKey)).isFalse();
        assertThat(fetch(link).statusCode()).as("an old link no longer works").isEqualTo(404);
        assertStatus(getAs(owner, "/api/media/" + id), 404);
        assertStatus(deleteAs(owner, "/api/media/" + id), 404);
        assertThat(countLogs(id, "DELETE")).isEqualTo(1);
    }

    @Test
    void aFileUsedByAMessageCannotBeDeleted() throws Exception {
        UUID id = uploadOk(owner, "photo.png", "image/png", Samples.png(20, 20), "IMAGE");
        assertStatus(attach(id, owner, "IMAGE", owner + ":key-1234567890"), 200);

        MvcResult result = deleteAs(owner, "/api/media/" + id);

        assertError(result, 409, "MEDIA_IN_USE");
        assertThat(statusOf(id)).isEqualTo("ATTACHED");
        assertThat(objectExists(objectKeyOf(id))).isTrue();
    }
}
