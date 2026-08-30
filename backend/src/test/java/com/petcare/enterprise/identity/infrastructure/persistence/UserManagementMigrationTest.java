package com.petcare.enterprise.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UserManagementMigrationTest extends PostgreSqlIntegrationTest {

    private static final String SCHEMA = "hu007_upgrade";
    private static final UUID TENANT_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID ROLE_ID = UUID.fromString("70000000-0000-4000-8000-000000000002");

    @BeforeAll
    static void upgradeFromV3() throws Exception {
        Flyway v3 = flyway("3");
        v3.migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO tenants (id, name, slug, plan, status, created_at)
                    VALUES ('70000000-0000-4000-8000-000000000001', 'Upgrade Clinic',
                            'upgrade-clinic', 'STARTER', 'ACTIVO', now())
                    """);
            statement.executeUpdate("""
                    INSERT INTO roles (id, tenant_id, name, description, system, created_at)
                    VALUES ('70000000-0000-4000-8000-000000000002',
                            '70000000-0000-4000-8000-000000000001',
                            'ADMINISTRADOR', 'Existing administrator', true, now())
                    """);
        }
        flyway("4").migrate();
    }

    @Test
    void addsOptimisticColumnsAndOnlyHu007Permissions() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet columns = statement.executeQuery("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = 'users'
                      AND column_name IN ('updated_at', 'version')
                    ORDER BY column_name
                    """)) {
                assertThat(values(columns)).containsExactly("updated_at", "version");
            }
            try (ResultSet permissions = statement.executeQuery("""
                    SELECT p.code
                    FROM role_permissions rp
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE rp.tenant_id = '70000000-0000-4000-8000-000000000001'
                      AND rp.role_id = '70000000-0000-4000-8000-000000000002'
                    ORDER BY p.code
                    """)) {
                assertThat(values(permissions)).containsExactly("USER_MANAGE", "USER_READ");
            }
        }
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection connection() throws Exception {
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + SCHEMA);
        }
        return connection;
    }

    private static List<String> values(ResultSet result) throws Exception {
        List<String> values = new ArrayList<>();
        while (result.next()) {
            values.add(result.getString(1));
        }
        return values;
    }
}
