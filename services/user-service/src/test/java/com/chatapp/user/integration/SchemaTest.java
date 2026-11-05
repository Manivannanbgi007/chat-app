package com.chatapp.user.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.chatapp.user.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** The application booted on a real MySQL: Flyway ran and Hibernate (ddl-auto=validate) accepts the schema. */
class SchemaTest extends AbstractIntegrationTest {

    @Test
    void allTablesAreCreatedByFlyway() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);

        assertThat(tables).contains("users", "user_profiles", "user_privacy_settings", "contacts",
                "blocked_users", "processed_events", "flyway_schema_history");
    }

    @Test
    void idsAreStoredAsReadableChar36() {
        String type = jdbc.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'contacts' AND column_name = 'id'",
                String.class);

        assertThat(type).isEqualToIgnoringCase("char(36)");
    }
}
