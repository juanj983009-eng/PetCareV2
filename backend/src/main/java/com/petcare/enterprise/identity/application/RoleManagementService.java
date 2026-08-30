package com.petcare.enterprise.identity.application;

import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditMetadata;
import com.petcare.enterprise.audit.application.AuditMetadataProvider;
import com.petcare.enterprise.audit.application.AuditRecord;
import com.petcare.enterprise.identity.application.RoleManagementUseCase.ManagedRole;
import com.petcare.enterprise.identity.application.RoleManagementUseCase.Permission;
import com.petcare.enterprise.identity.application.RoleManagementUseCase.RoleCatalog;
import com.petcare.enterprise.identity.application.RoleManagementUseCase.UserRoles;
import com.petcare.enterprise.identity.domain.UserStatus;
import com.petcare.enterprise.tenancy.application.TenantContext;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleManagementService implements RoleManagementUseCase {

    private static final Set<String> ADMINISTRATOR_REQUIRED = Set.of(
            "DASHBOARD_VIEW", "USER_READ", "USER_MANAGE", "ROLE_READ", "ROLE_MANAGE");

    private final TenantContext tenantContext;
    private final RoleManagementRepository roles;
    private final UserManagementRepository users;
    private final AuditMetadataProvider auditMetadataProvider;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public RoleManagementService(
            TenantContext tenantContext,
            RoleManagementRepository roles,
            UserManagementRepository users,
            AuditMetadataProvider auditMetadataProvider,
            AuditLogWriter auditLogWriter,
            Clock clock) {
        this.tenantContext = tenantContext;
        this.roles = roles;
        this.users = users;
        this.auditMetadataProvider = auditMetadataProvider;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public RoleCatalog catalog() {
        UUID tenantId = tenantContext.requireTenant().tenantId();
        return new RoleCatalog(roles.findRoles(tenantId), roles.findPermissions());
    }

    @Override
    @Transactional(readOnly = true)
    public ManagedRole get(UUID roleId) {
        UUID tenantId = tenantContext.requireTenant().tenantId();
        return roles.findRole(tenantId, Objects.requireNonNull(roleId, "Role id is required"))
                .orElseThrow(RoleManagementException::roleNotFound);
    }

    @Override
    @Transactional
    public ManagedRole replacePermissions(UUID roleId, List<UUID> permissionIds) {
        AuditMetadata metadata = auditMetadataProvider.requireTenantMetadata();
        ManagedRole role = roles.findRole(metadata.tenantId(), Objects.requireNonNull(roleId, "Role id is required"))
                .orElseThrow(RoleManagementException::roleNotFound);
        List<UUID> normalized = normalizedIds(permissionIds);
        Set<UUID> knownPermissions = roles.findPermissions().stream().map(Permission::id).collect(java.util.stream.Collectors.toSet());
        if (!knownPermissions.containsAll(normalized)) {
            throw RoleManagementException.permissionNotFound();
        }
        if (isAdministrator(role) && !requiredPermissionsPresent(normalized)) {
            throw RoleManagementException.protectedAdministrator();
        }
        roles.replaceRolePermissions(metadata.tenantId(), role.id(), normalized, clock.instant());
        ManagedRole updated = roles.findRole(metadata.tenantId(), role.id()).orElseThrow();
        appendAudit(metadata, "ROLE", role.id(), "PERMISSIONS_UPDATED", permissionData(role.permissions()), permissionData(updated.permissions()));
        return updated;
    }

    @Override
    @Transactional
    public UserRoles replaceUserRoles(UUID userId, List<UUID> roleIds, long version) {
        AuditMetadata metadata = auditMetadataProvider.requireTenantMetadata();
        UUID targetId = Objects.requireNonNull(userId, "User id is required");
        var target = users.findByTenantIdAndId(metadata.tenantId(), targetId)
                .orElseThrow(RoleManagementException::userNotFound);
        List<UUID> normalized = normalizedIds(roleIds);
        if (!roles.allRolesExist(metadata.tenantId(), normalized)) {
            throw RoleManagementException.roleNotFound();
        }
        Map<UUID, ManagedRole> rolesById = roles.findRoles(metadata.tenantId()).stream()
                .collect(java.util.stream.Collectors.toMap(ManagedRole::id, value -> value));
        boolean grantsAdministrator = normalized.stream().map(rolesById::get).anyMatch(this::isAdministrator);
        if (target.status() == UserStatus.ACTIVO
                && roles.userHasAdministratorRole(metadata.tenantId(), targetId)
                && !grantsAdministrator
                && !roles.hasActiveAdministrator(metadata.tenantId(), targetId)) {
            throw RoleManagementException.protectedAdministrator();
        }
        List<UUID> previousRoleIds = roles.findUserRoleIds(metadata.tenantId(), targetId);
        UserRoles updated = roles.replaceUserRoles(
                        metadata.tenantId(), targetId, normalized, version, clock.instant())
                .orElseThrow(RoleManagementException::concurrentConflict);
        appendAudit(metadata, "USER", targetId, "ROLES_REPLACED", Map.of("roleIds", previousRoleIds), Map.of("roleIds", updated.roleIds()));
        return updated;
    }

    private boolean requiredPermissionsPresent(List<UUID> permissionIds) {
        return roles.findPermissions().stream()
                .filter(permission -> permissionIds.contains(permission.id()))
                .map(Permission::code)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(ADMINISTRATOR_REQUIRED);
    }

    private static List<UUID> normalizedIds(List<UUID> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (UUID value : values) {
            ids.add(Objects.requireNonNull(value, "Ids cannot contain null"));
        }
        return List.copyOf(ids);
    }

    private boolean isAdministrator(ManagedRole role) {
        return role != null && "ADMINISTRADOR".equalsIgnoreCase(role.name());
    }

    private void appendAudit(AuditMetadata metadata, String entityType, UUID entityId, String action,
            Map<String, Object> oldData, Map<String, Object> newData) {
        auditLogWriter.append(new AuditRecord(
                UUID.randomUUID(), metadata.tenantId(), metadata.actorUserId(), metadata.branchId(), entityType,
                entityId, action, clock.instant(), null, metadata.correlationId(), oldData, newData));
    }

    private static Map<String, Object> permissionData(List<Permission> permissions) {
        return Map.of("permissionCodes", permissions.stream().map(Permission::code).sorted().toList());
    }
}
