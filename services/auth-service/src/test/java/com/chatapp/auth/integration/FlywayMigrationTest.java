package com.chatapp.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.chatapp.auth.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** Boots the whole application on a real MySQL: Flyway ran and Hibernate accepts the schema. */
class FlywayMigrationTest extends AbstractIntegrationTest {

    @Test
    void allTablesAreCreatedByFlyway() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);
        assertThat(tables).contains("auth_users", "login_sessions", "refresh_tokens", "otp_verifications",
                "password_reset_tokens", "audit_logs", "outbox_events", "flyway_schema_history");
    }

    @Test
    void flywayAppliedBothMigrations() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertThat(applied).isEqualTo(2);
    }
}
