package com.petcare.enterprise.identity.infrastructure.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(properties = {
        "petcare.bootstrap.enabled=true",
        "petcare.bootstrap.tenant-name=Fictional Bootstrap Clinic",
        "petcare.bootstrap.tenant-slug=enabled-bootstrap-fixture",
        "petcare.bootstrap.tenant-plan=STARTER",
        "petcare.bootstrap.branch-name=Fictional Main Branch",
        "petcare.bootstrap.branch-time-zone=America/Lima",
        "petcare.bootstrap.admin-first-name=Alex",
        "petcare.bootstrap.admin-last-name=Fictional",
        "petcare.bootstrap.admin-email=bootstrap@example.test",
        "petcare.bootstrap.admin-password=Fictional-Bootstrap-Password-123!"
})
class IdentityBootstrapEnabledIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private IdentityBootstrapService bootstrapService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void enabledBootstrapIsIdempotentAndStoresOnlyPasswordHash() {
        Map<String, Object> first = row();
        String originalHash = (String) first.get("password_hash");
        assertThat(originalHash).startsWith("$2");
        assertThat(originalHash).doesNotContain("Fictional-Bootstrap-Password-123!");
        assertThat(passwordEncoder.matches("Fictional-Bootstrap-Password-123!", originalHash)).isTrue();

        bootstrapService.run();

        Map<String, Object> second = row();
        assertThat(second.get("password_hash")).isEqualTo(originalHash);
        assertThat(count("tenants", "slug = 'enabled-bootstrap-fixture'")).isOne();
        assertThat(count("users", "email = 'bootstrap@example.test'")).isOne();
        assertThat(count("roles", "tenant_id = (SELECT id FROM tenants WHERE slug = 'enabled-bootstrap-fixture') AND name = 'ADMINISTRADOR'")).isOne();
        assertThat(count("permissions", "code = 'DASHBOARD_VIEW'")).isOne();
        assertThat(count("user_roles", "true")).isOne();
        assertThat(jdbcTemplate.queryForList("""
                SELECT p.code
                FROM role_permissions rp
                JOIN permissions p ON p.id = rp.permission_id
                JOIN tenants t ON t.id = rp.tenant_id
                JOIN roles r ON r.tenant_id = rp.tenant_id AND r.id = rp.role_id
                WHERE t.slug = 'enabled-bootstrap-fixture' AND r.name = 'ADMINISTRADOR'
                ORDER BY p.code
                """, String.class))
                .containsExactly("APPOINTMENT_CREATE", "APPOINTMENT_READ", "DASHBOARD_VIEW", "OWNER_CREATE", "OWNER_READ", "OWNER_UPDATE", "PATIENT_CREATE", "PATIENT_READ", "PATIENT_UPDATE", "ROLE_MANAGE", "ROLE_READ", "USER_MANAGE", "USER_READ");
        assertThat(count("user_branches", "primary_branch = true")).isOne();
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap("""
                SELECT u.password_hash, u.tenant_id, ub.branch_id
                FROM users u
                JOIN user_branches ub
                  ON ub.tenant_id = u.tenant_id AND ub.user_id = u.id AND ub.primary_branch = true
                WHERE u.email = 'bootstrap@example.test'
                """);
    }

    private int count(String table, String predicate) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + predicate,
                Integer.class);
    }
}
