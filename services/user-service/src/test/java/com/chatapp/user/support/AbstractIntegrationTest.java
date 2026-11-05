package com.chatapp.user.support;

import com.chatapp.user.TestJwt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Base class for tests that need the real thing: a MySQL and a Kafka in Docker (Docker must be running).
 * The containers are static and started once, so every test class shares them (much faster),
 * and all test classes share one Spring context. Because the database is shared, each test
 * creates its own users with random names.
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "JWT_SECRET=" + TestJwt.SECRET,
        "INTERNAL_SERVICE_TOKEN=" + TestJwt.INTERNAL_TOKEN,
        "USER_DB_PASSWORD=unused-testcontainers-provides-the-password"
})
public abstract class AbstractIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");

    static {
        MYSQL.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    public static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
