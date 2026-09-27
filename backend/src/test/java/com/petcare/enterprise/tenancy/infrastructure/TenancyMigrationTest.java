package com.petcare.enterprise.tenancy.infrastructure;

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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TenancyMigrationTest extends PostgreSqlIntegrationTest {

    private static final String SCHEMA = "hu002_migration_test";
    private static Flyway flyway;

    @BeforeAll
    static void migrateAnEmptySchema() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }

    @Test
    void appliesAllMigrationsFromAnEmptyPostgreSqlSchema() {
        assertThat(flyway.info().applied())
                .filteredOn(info -> info.getVersion() != null)
                .extracting(info -> info.getVersion().toString())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
    }

    @Test
    void createsRelevantConstraintsAndIndexes() throws SQLException {
        Set<String> indexes = new HashSet<>();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT indexname FROM pg_indexes WHERE schemaname = ?")) {
            statement.setString(1, SCHEMA);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    indexes.add(resultSet.getString(1));
                }
            }
        }

        assertThat(indexes).contains(
                "uk_tenants_ruc_not_null",
                "idx_branches_tenant_active",
                "idx_audit_entries_tenant_entity",
                "idx_audit_entries_tenant_occurred_at",
                "idx_audit_entries_branch_id",
                "idx_patients_tenant_status_species",
                "idx_patients_name_search",
                "idx_patients_microchip_search");
    }

    @Test
    void rejectsDuplicateSlugAtPostgreSqlLevel() throws SQLException {
        String slug = unique("duplicate-slug");
        insertTenant(slug, null);

        assertThatThrownBy(() -> insertTenant(slug, null)).isInstanceOf(SQLException.class);
    }

    @Test
    void allowsMultipleNullRucsAndRejectsRepeatedNonNullRuc() throws SQLException {
        insertTenant(unique("null-ruc-a"), null);
        insertTenant(unique("null-ruc-b"), null);

        String ruc = digits();
        insertTenant(unique("ruc-a"), ruc);
        assertThatThrownBy(() -> insertTenant(unique("ruc-b"), ruc)).isInstanceOf(SQLException.class);
    }

    @Test
    void scopesBranchNameUniquenessToItsTenantAndPreservesRelationship() throws SQLException {
        UUID tenantA = insertTenant(unique("branch-tenant-a"), null);
        UUID tenantB = insertTenant(unique("branch-tenant-b"), null);
        UUID branchA = insertBranch(tenantA, "Sede Principal");
        insertBranch(tenantB, "Sede Principal");

        assertThatThrownBy(() -> insertBranch(tenantA, "Sede Principal"))
                .isInstanceOf(SQLException.class);

        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT tenant_id FROM branches WHERE id = ?")) {
            statement.setObject(1, branchA);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getObject(1, UUID.class)).isEqualTo(tenantA);
            }
        }
    }

    @Test
    void persistsAuditAndRejectsUpdateAndDelete() throws SQLException {
        UUID tenantId = insertTenant(unique("audit-tenant"), null);
        UUID auditId = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO audit_entries (
                            id, tenant_id, entity_type, entity_id, action,
                            occurred_at, correlation_id, new_data
                        ) VALUES (?, ?, 'TENANT', ?, 'TENANT_CREATED', ?, ?, '{}'::jsonb)
                        """)) {
            statement.setObject(1, auditId);
            statement.setObject(2, tenantId);
            statement.setObject(3, tenantId);
            statement.setObject(4, OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
            statement.setObject(5, UUID.randomUUID());
            assertThat(statement.executeUpdate()).isOne();
        }

        assertThatThrownBy(() -> execute("UPDATE audit_entries SET action = 'CHANGED' WHERE id = '" + auditId + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM audit_entries WHERE id = '" + auditId + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
    }

    private static UUID insertTenant(String slug, String ruc) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO tenants (id, name, ruc, slug, plan, status, created_at)
                        VALUES (?, 'Fictional Veterinary Clinic', ?, ?, 'FREE', 'ACTIVO', ?)
                        """)) {
            statement.setObject(1, id);
            statement.setString(2, ruc);
            statement.setString(3, slug);
            statement.setObject(4, OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertBranch(UUID tenantId, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO branches (
                            id, tenant_id, name, time_zone, active, created_at
                        ) VALUES (?, ?, ?, 'America/Lima', true, ?)
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, name);
            statement.setObject(4, OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
            statement.executeUpdate();
        }
        return id;
    }

    private static void execute(String sql) throws SQLException {
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

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String digits() {
        return Long.toUnsignedString(Math.abs(UUID.randomUUID().getMostSignificantBits())).substring(0, 15);
    }
}
