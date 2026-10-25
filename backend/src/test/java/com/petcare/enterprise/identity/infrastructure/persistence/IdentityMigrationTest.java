package com.petcare.enterprise.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IdentityMigrationTest extends PostgreSqlIntegrationTest {

    private static final String SCHEMA = "hu005_identity_migration";

    @BeforeAll
    static void migrateEmptySchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void appliesV1ThroughV8FromEmptySchema() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = true AND version IS NOT NULL
                        ORDER BY installed_rank
                        """)) {
            List<String> versions = new ArrayList<>();
            while (result.next()) {
                versions.add(result.getString(1));
            }
            assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17");
        }
    }

    @Test
    void enforcesTenantEmailAndRoleUniquenessWhilePermissionsRemainGlobal() throws SQLException {
        UUID tenantA = insertTenant("identity-a");
        UUID tenantB = insertTenant("identity-b");
        insertUser(tenantA, "same@example.test");
        insertUser(tenantB, "same@example.test");
        assertThatThrownBy(() -> insertUser(tenantA, "same@example.test"))
                .isInstanceOf(SQLException.class);

        insertRole(tenantA, "ADMINISTRADOR");
        insertRole(tenantB, "ADMINISTRADOR");
        assertThatThrownBy(() -> insertRole(tenantA, "administrador"))
                .isInstanceOf(SQLException.class);

        insertPermission("DASHBOARD_VIEW");
        assertThatThrownBy(() -> insertPermission("DASHBOARD_VIEW"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void rejectsCrossTenantRoleAndBranchAssignments() throws SQLException {
        UUID tenantA = insertTenant("relations-a");
        UUID tenantB = insertTenant("relations-b");
        UUID userA = insertUser(tenantA, "a@example.test");
        UUID roleB = insertRole(tenantB, "ADMINISTRADOR");
        UUID branchB = insertBranch(tenantB);

        assertThatThrownBy(() -> executeUpdate("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                VALUES ('%s', '%s', '%s', now())
                """.formatted(tenantA, userA, roleB))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeUpdate("""
                INSERT INTO user_branches (tenant_id, user_id, branch_id, primary_branch, assigned_at)
                VALUES ('%s', '%s', '%s', true, now())
                """.formatted(tenantA, userA, branchB))).isInstanceOf(SQLException.class);
    }

    @Test
    void acceptsAuthenticationEventsWithAndWithoutResolvedIdentity() throws SQLException {
        UUID tenantId = insertTenant("events");
        UUID userId = insertUser(tenantId, "events@example.test");
        UUID resolved = insertEvent(tenantId, userId, "SUCCESS", "AUTHENTICATED");
        UUID unresolved = insertEvent(null, null, "FAILURE", "TENANT_NOT_FOUND");

        assertThat(count("authentication_events", resolved)).isOne();
        assertThat(count("authentication_events", unresolved)).isOne();
        assertThatThrownBy(() -> insertEvent(null, userId, "FAILURE", "BAD_PASSWORD"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void authenticationEventsRejectUpdateDeleteAndTruncate() throws SQLException {
        UUID eventId = insertEvent(null, null, "FAILURE", "TENANT_NOT_FOUND");

        assertThatThrownBy(() -> executeUpdate(
                "UPDATE authentication_events SET internal_reason = 'USER_NOT_FOUND' WHERE id = '" + eventId + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeUpdate(
                "DELETE FROM authentication_events WHERE id = '" + eventId + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeUpdate("TRUNCATE authentication_events"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
    }

    private static UUID insertTenant(String prefix) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO tenants (id, name, slug, plan, status, created_at)
                        VALUES (?, 'Fictional Clinic', ?, 'STARTER', 'ACTIVO', ?)
                        """)) {
            statement.setObject(1, id);
            statement.setString(2, prefix + "-" + id.toString().substring(0, 8));
            statement.setObject(3, now());
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertBranch(UUID tenantId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO branches (id, tenant_id, name, time_zone, active, created_at)
                        VALUES (?, ?, ?, 'America/Lima', true, ?)
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, "Branch " + id.toString().substring(0, 8));
            statement.setObject(4, now());
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertUser(UUID tenantId, String email) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO users (
                            id, tenant_id, first_name, last_name, email,
                            password_hash, status, created_at
                        ) VALUES (?, ?, 'Alex', 'Fictional', ?, '$2a$12$fictionalhash', 'ACTIVO', ?)
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, email);
            statement.setObject(4, now());
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertRole(UUID tenantId, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO roles (id, tenant_id, name, system, created_at)
                        VALUES (?, ?, ?, true, ?)
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, name);
            statement.setObject(4, now());
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertPermission(String code) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO permissions (id, code, description, module, created_at)
                        VALUES (?, ?, 'Fictional permission', 'IDENTITY', ?)
                        """)) {
            statement.setObject(1, id);
            statement.setString(2, code);
            statement.setObject(3, now());
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertEvent(UUID tenantId, UUID userId, String result, String reason) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO authentication_events (
                            id, tenant_id, user_id, event_type, result,
                            internal_reason, occurred_at, correlation_id
                        ) VALUES (?, ?, ?, 'LOGIN', ?, ?, ?, ?)
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setObject(3, userId);
            statement.setString(4, result);
            statement.setString(5, reason);
            statement.setObject(6, now());
            statement.setObject(7, UUID.randomUUID());
            statement.executeUpdate();
        }
        return id;
    }

    private static int count(String table, UUID id) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM " + table + " WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void executeUpdate(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + SCHEMA);
        }
        return connection;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }
}
