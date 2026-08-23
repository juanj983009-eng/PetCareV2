package com.petcare.enterprise.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.tenancy.domain.TenantPlan;
import com.petcare.enterprise.tenancy.domain.TenantStatus;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RegisterTenantRollbackIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private RegisterTenantUseCase registerTenantUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AuditLogWriter auditLogWriter;

    @Test
    void rollsBackTenantAndBranchWhenAuditFails() {
        String slug = "rollback-" + UUID.randomUUID().toString().substring(0, 8);
        doThrow(new IllegalStateException("Simulated audit failure")).when(auditLogWriter).append(any());

        RegisterTenantCommand command = new RegisterTenantCommand(
                "Rollback Fictional Clinic",
                null,
                slug,
                TenantPlan.FREE,
                TenantStatus.ACTIVO,
                "Rollback Main Branch",
                null,
                null,
                "America/Lima",
                UUID.randomUUID());

        assertThatThrownBy(() -> registerTenantUseCase.register(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated audit failure");

        Integer tenants = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenants WHERE slug = ?", Integer.class, slug);
        Integer branches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM branches WHERE name = ?", Integer.class, "Rollback Main Branch");
        assertThat(tenants).isZero();
        assertThat(branches).isZero();
    }
}
