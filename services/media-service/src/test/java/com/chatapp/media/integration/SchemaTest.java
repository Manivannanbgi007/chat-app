package com.chatapp.media.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.chatapp.media.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** Starting the whole application on a real MySQL proves that Flyway ran and Hibernate accepts the schema. */
class SchemaTest extends AbstractIntegrationTest {

    @Test
    void allTablesAreCreatedByFlyway() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);
        assertThat(tables).contains("media_files", "media_metadata", "media_access_logs", "flyway_schema_history");
    }

    @Test
    void theMigrationWasApplied() {
        Integer applied = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void theBucketWasPreparedByTheService() {
        // the service creates the bucket in the background after startup, or at the first upload
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(AbstractIntegrationTest.TEST_S3.listBuckets().buckets().stream().map(b -> b.name()).toList())
                .contains(BUCKET);
    }
}
