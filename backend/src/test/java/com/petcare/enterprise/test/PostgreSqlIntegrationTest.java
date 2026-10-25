package com.petcare.enterprise.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class PostgreSqlIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("petcare_test")
            .withUsername("petcare_test")
            .withPassword("local-test-only");

    static {
        POSTGRES.start();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Failed to initialize test postgres extensions", e);
        }
    }

    @DynamicPropertySource
    static void postgresqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("petcare.security.jwt.secret", () -> "test-only-jwt-secret-that-is-long-enough-1234567890");
        registry.add("petcare.security.jwt.issuer", () -> "https://petcare.test");
        registry.add("petcare.security.jwt.access-token-ttl", () -> "PT15M");
    }
}
