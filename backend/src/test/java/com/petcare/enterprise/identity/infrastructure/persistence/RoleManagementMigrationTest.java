package com.petcare.enterprise.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RoleManagementMigrationTest extends PostgreSqlIntegrationTest {

    private static final String SCHEMA = "hu008_upgrade";

    @BeforeAll
    static void upgradeFromV4() throws Exception {
        flyway("4").migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO tenants (id, name, slug, plan, status, created_at)
                    VALUES ('80000000-0000-4000-8000-000000000001', 'Upgrade Clinic',
                            'role-upgrade-clinic', 'STARTER', 'ACTIVO', now())
                    """);
        }
        flyway(null).migrate();
    }

    @Test
    void provisionsFourTenantOwnedSystemRolesAndGlobalRolePermissions() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet roles = statement.executeQuery("""
                    SELECT name FROM roles
                    WHERE tenant_id = '80000000-0000-4000-8000-000000000001'
                    ORDER BY name
                    """)) {
                assertThat(values(roles)).containsExactly("ADMINISTRADOR", "ALMACEN", "RECEPCIONISTA", "VETERINARIO");
            }
            try (ResultSet permissions = statement.executeQuery("""
                    SELECT code FROM permissions WHERE code IN ('ROLE_READ', 'ROLE_MANAGE') ORDER BY code
                    """)) {
                assertThat(values(permissions)).containsExactly("ROLE_MANAGE", "ROLE_READ");
            }
        }
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true).locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static Connection connection() throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement statement = connection.createStatement()) { statement.execute("SET search_path TO " + SCHEMA); }
        return connection;
    }

    private static List<String> values(ResultSet result) throws Exception {
        List<String> values = new ArrayList<>();
        while (result.next()) values.add(result.getString(1));
        return values;
    }
}
