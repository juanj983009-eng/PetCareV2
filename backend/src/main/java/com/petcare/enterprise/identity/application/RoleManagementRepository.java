package com.petcare.enterprise.identity.application;

import com.petcare.enterprise.identity.application.RoleManagementUseCase.ManagedRole;
import com.petcare.enterprise.identity.application.RoleManagementUseCase.Permission;
import com.petcare.enterprise.identity.application.RoleManagementUseCase.UserRoles;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleManagementRepository {

    List<ManagedRole> findRoles(UUID tenantId);

    List<Permission> findPermissions();

    Optional<ManagedRole> findRole(UUID tenantId, UUID roleId);

    boolean allRolesExist(UUID tenantId, List<UUID> roleIds);

    Optional<UserRoles> replaceUserRoles(UUID tenantId, UUID userId, List<UUID> roleIds, long version, Instant updatedAt);

    boolean hasActiveAdministrator(UUID tenantId, UUID excludingUserId);

    boolean userHasAdministratorRole(UUID tenantId, UUID userId);

    List<UUID> findUserRoleIds(UUID tenantId, UUID userId);

    void replaceRolePermissions(UUID tenantId, UUID roleId, List<UUID> permissionIds, Instant assignedAt);
}
