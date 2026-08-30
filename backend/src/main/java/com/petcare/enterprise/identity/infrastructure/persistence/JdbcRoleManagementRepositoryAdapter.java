package com.petcare.enterprise.identity.infrastructure.persistence;

import com.petcare.enterprise.identity.application.RoleManagementRepository;
import com.petcare.enterprise.identity.application.RoleManagementUseCase.ManagedRole;
import com.petcare.enterprise.identity.application.RoleManagementUseCase.Permission;
import com.petcare.enterprise.identity.application.RoleManagementUseCase.UserRoles;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRoleManagementRepositoryAdapter implements RoleManagementRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcRoleManagementRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ManagedRole> findRoles(UUID tenantId) {
        return jdbc.query("SELECT id, name, description, system FROM roles WHERE tenant_id = :tenantId ORDER BY name, id",
                Map.of("tenantId", tenantId), (result, row) -> role(result, tenantId));
    }

    @Override
    public List<Permission> findPermissions() {
        return jdbc.query("SELECT id, code, description, module FROM permissions ORDER BY code",
                (result, row) -> permission(result));
    }

    @Override
    public Optional<ManagedRole> findRole(UUID tenantId, UUID roleId) {
        return jdbc.query("SELECT id, name, description, system FROM roles WHERE tenant_id = :tenantId AND id = :roleId",
                Map.of("tenantId", tenantId, "roleId", roleId), (result, row) -> role(result, tenantId)).stream().findFirst();
    }

    @Override
    public boolean allRolesExist(UUID tenantId, List<UUID> roleIds) {
        if (roleIds.isEmpty()) return true;
        Integer count = jdbc.queryForObject("SELECT count(*) FROM roles WHERE tenant_id = :tenantId AND id IN (:roleIds)",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("roleIds", roleIds), Integer.class);
        return count != null && count == roleIds.size();
    }

    @Override
    public Optional<UserRoles> replaceUserRoles(UUID tenantId, UUID userId, List<UUID> roleIds, long version, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE users SET updated_at = :updatedAt, version = version + 1
                 WHERE tenant_id = :tenantId AND id = :userId AND version = :version
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("userId", userId)
                .addValue("version", version).addValue("updatedAt", Timestamp.from(updatedAt)));
        if (updated != 1) return Optional.empty();
        jdbc.update("DELETE FROM user_roles WHERE tenant_id = :tenantId AND user_id = :userId",
                Map.of("tenantId", tenantId, "userId", userId));
        for (UUID roleId : roleIds) {
            jdbc.update("""
                    INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                    VALUES (:tenantId, :userId, :roleId, :assignedAt)
                    """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("userId", userId)
                    .addValue("roleId", roleId).addValue("assignedAt", Timestamp.from(updatedAt)));
        }
        return Optional.of(new UserRoles(userId, List.copyOf(roleIds), version + 1));
    }

    @Override
    public boolean hasActiveAdministrator(UUID tenantId, UUID excludingUserId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM users u
                JOIN user_roles ur ON ur.tenant_id = u.tenant_id AND ur.user_id = u.id
                JOIN roles r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
                WHERE u.tenant_id = :tenantId AND u.status = 'ACTIVO'
                  AND upper(r.name) = 'ADMINISTRADOR' AND u.id <> :excludingUserId
                """, Map.of("tenantId", tenantId, "excludingUserId", excludingUserId), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public boolean userHasAdministratorRole(UUID tenantId, UUID userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM user_roles ur
                JOIN roles r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
                WHERE ur.tenant_id = :tenantId AND ur.user_id = :userId AND upper(r.name) = 'ADMINISTRADOR'
                """, Map.of("tenantId", tenantId, "userId", userId), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public List<UUID> findUserRoleIds(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT role_id FROM user_roles
                WHERE tenant_id = :tenantId AND user_id = :userId
                ORDER BY role_id
                """, Map.of("tenantId", tenantId, "userId", userId),
                (result, row) -> result.getObject("role_id", UUID.class));
    }

    @Override
    public void replaceRolePermissions(UUID tenantId, UUID roleId, List<UUID> permissionIds, Instant assignedAt) {
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id = :tenantId AND role_id = :roleId",
                Map.of("tenantId", tenantId, "roleId", roleId));
        for (UUID permissionId : permissionIds) {
            jdbc.update("""
                    INSERT INTO role_permissions (tenant_id, role_id, permission_id, assigned_at)
                    VALUES (:tenantId, :roleId, :permissionId, :assignedAt)
                    """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("roleId", roleId)
                    .addValue("permissionId", permissionId).addValue("assignedAt", Timestamp.from(assignedAt)));
        }
    }

    private ManagedRole role(ResultSet result, UUID tenantId) throws SQLException {
        UUID roleId = result.getObject("id", UUID.class);
        List<Permission> permissions = jdbc.query("""
                SELECT p.id, p.code, p.description, p.module
                FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                WHERE rp.tenant_id = :tenantId AND rp.role_id = :roleId ORDER BY p.code
                """, Map.of("tenantId", tenantId, "roleId", roleId), (row, index) -> permission(row));
        return new ManagedRole(roleId, result.getString("name"), result.getString("description"),
                result.getBoolean("system"), permissions);
    }

    private static Permission permission(ResultSet result) throws SQLException {
        return new Permission(result.getObject("id", UUID.class), result.getString("code"),
                result.getString("description"), result.getString("module"));
    }
}
