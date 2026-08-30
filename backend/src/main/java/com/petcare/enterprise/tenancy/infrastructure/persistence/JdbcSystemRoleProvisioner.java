package com.petcare.enterprise.tenancy.infrastructure.persistence;

import com.petcare.enterprise.tenancy.application.SystemRoleProvisioner;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSystemRoleProvisioner implements SystemRoleProvisioner {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcSystemRoleProvisioner(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void ensureForTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT ensure_tenant_system_roles(:tenantId)", Map.of("tenantId", tenantId), Object.class);
    }
}
