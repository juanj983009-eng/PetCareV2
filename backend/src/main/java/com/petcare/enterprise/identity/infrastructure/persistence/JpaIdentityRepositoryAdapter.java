package com.petcare.enterprise.identity.infrastructure.persistence;

import com.petcare.enterprise.identity.application.BootstrapIdentity;
import com.petcare.enterprise.identity.application.BootstrapIdentityResult;
import com.petcare.enterprise.identity.application.IdentityRepository;
import com.petcare.enterprise.identity.application.LoginIdentity;
import com.petcare.enterprise.identity.domain.EmailAddress;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaIdentityRepositoryAdapter implements IdentityRepository {

    private static final String ADMINISTRATOR_ROLE = "ADMINISTRADOR";

    private final SpringDataUserRepository users;
    private final SpringDataRoleRepository roles;
    private final SpringDataPermissionRepository permissions;
    private final EntityManager entityManager;

    JpaIdentityRepositoryAdapter(
            SpringDataUserRepository users,
            SpringDataRoleRepository roles,
            SpringDataPermissionRepository permissions,
            EntityManager entityManager) {
        this.users = users;
        this.roles = roles;
        this.permissions = permissions;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<LoginIdentity> findForLogin(UUID tenantId, EmailAddress email) {
        return users.findByTenantIdAndEmail(tenantId, email.value())
                .map(user -> new LoginIdentity(
                        user.toDomain(),
                        users.findPrimaryBranchId(tenantId, user.id()).orElse(null)));
    }

    @Override
    public Set<String> findEffectiveAuthorities(UUID tenantId, UUID userId) {
        return new LinkedHashSet<>(users.findEffectiveAuthorities(tenantId, userId));
    }

    @Override
    public void updateLastAccess(UUID tenantId, UUID userId, Instant lastAccessAt) {
        if (users.updateLastAccess(tenantId, userId, lastAccessAt) != 1) {
            throw new IllegalStateException("Authenticated user no longer exists");
        }
    }

    @Override
    public BootstrapIdentityResult ensureBootstrapIdentity(BootstrapIdentity command) {
        PermissionJpaEntity dashboard = permissions.findByCode(PermissionJpaEntity.DASHBOARD_VIEW)
                .orElseGet(() -> permissions.saveAndFlush(
                        PermissionJpaEntity.bootstrapDashboardView(command.createdAt())));
        PermissionJpaEntity userRead = permissions.findByCode(PermissionJpaEntity.USER_READ)
                .orElseGet(() -> permissions.saveAndFlush(
                        PermissionJpaEntity.bootstrapUserRead(command.createdAt())));
        PermissionJpaEntity userManage = permissions.findByCode(PermissionJpaEntity.USER_MANAGE)
                .orElseGet(() -> permissions.saveAndFlush(
                        PermissionJpaEntity.bootstrapUserManage(command.createdAt())));
        RoleJpaEntity role = roles.findByTenantIdAndNameIgnoreCase(command.tenantId(), ADMINISTRATOR_ROLE)
                .orElseGet(() -> roles.saveAndFlush(
                        RoleJpaEntity.bootstrapAdministrator(command.tenantId(), command.createdAt())));

        Optional<UserJpaEntity> existing = users.findByTenantIdAndEmail(command.tenantId(), command.email().value());
        boolean created = existing.isEmpty();
        UserJpaEntity user = existing.orElseGet(() -> users.saveAndFlush(UserJpaEntity.bootstrap(command)));

        insertAssociation("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                VALUES (:tenantId, :userId, :roleId, :assignedAt)
                ON CONFLICT DO NOTHING
                """, command.tenantId(), user.id(), role.id(), command.createdAt());
        insertAssociation("""
                INSERT INTO role_permissions (tenant_id, role_id, permission_id, assigned_at)
                VALUES (:tenantId, :userId, :roleId, :assignedAt)
                ON CONFLICT DO NOTHING
                """, command.tenantId(), role.id(), dashboard.id(), command.createdAt());
        insertAssociation("""
                INSERT INTO role_permissions (tenant_id, role_id, permission_id, assigned_at)
                VALUES (:tenantId, :userId, :roleId, :assignedAt)
                ON CONFLICT DO NOTHING
                """, command.tenantId(), role.id(), userRead.id(), command.createdAt());
        insertAssociation("""
                INSERT INTO role_permissions (tenant_id, role_id, permission_id, assigned_at)
                VALUES (:tenantId, :userId, :roleId, :assignedAt)
                ON CONFLICT DO NOTHING
                """, command.tenantId(), role.id(), userManage.id(), command.createdAt());
        entityManager.createNativeQuery("""
                        INSERT INTO user_branches (
                            tenant_id, user_id, branch_id, primary_branch, assigned_at
                        ) VALUES (:tenantId, :userId, :branchId, true, :assignedAt)
                        ON CONFLICT DO NOTHING
                        """)
                .setParameter("tenantId", command.tenantId())
                .setParameter("userId", user.id())
                .setParameter("branchId", command.branchId())
                .setParameter("assignedAt", command.createdAt())
                .executeUpdate();
        entityManager.flush();
        return new BootstrapIdentityResult(user.id(), created);
    }

    private void insertAssociation(
            String sql,
            UUID tenantId,
            UUID userId,
            UUID roleId,
            Instant assignedAt) {
        entityManager.createNativeQuery(sql)
                .setParameter("tenantId", tenantId)
                .setParameter("userId", userId)
                .setParameter("roleId", roleId)
                .setParameter("assignedAt", assignedAt)
                .executeUpdate();
    }
}
