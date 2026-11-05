package com.chatapp.status.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.status.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The lifetime really comes from the setting chatapp.status.lifetime-seconds (24 h by default). Here it is 2 seconds
 * and the clean-up job runs every 500 ms on its own, so a status disappears by itself and its file is released.
 * (Its own Spring context because of the different settings; the MySQL container is shared. The context is closed
 * after this class so its fast clean-up job cannot interfere with the tests that run the job by hand.)
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "chatapp.status.lifetime-seconds=2",
        "chatapp.status.cleanup-interval-ms=500"
})
class ShortLifetimeTest extends AbstractIntegrationTest {

    @Test
    void aStatusExpiresAfterTheConfiguredLifetimeAndTheJobRemovesIt() throws Exception {
        Person owner = newPerson("Owner");
        UUID mediaId = MEDIA.addUpload(owner.id(), "IMAGE", true);
        MvcResult posted = postMedia(owner, "IMAGE", mediaId, null, newKey());
        assertStatus(posted, 201);
        UUID statusId = UUID.fromString(field(posted, "$.data.id"));
        assertThat(Duration.between(Instant.parse(field(posted, "$.data.createdAt")),
                Instant.parse(field(posted, "$.data.expiresAt")))).isEqualTo(Duration.ofSeconds(2));
        assertThat((List<?>) read(get("/api/statuses/mine", owner), "$.data")).hasSize(1);

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !MEDIA.isDeleted(mediaId)) {
            Thread.sleep(200);
        }

        assertThat((List<?>) read(get("/api/statuses/mine", owner), "$.data")).isEmpty();
        assertThat(rows("SELECT COUNT(*) FROM statuses WHERE id = ?", statusId.toString())).isZero();
        assertThat(MEDIA.isDeleted(mediaId)).as("the file was released by the scheduled job").isTrue();
    }
}
