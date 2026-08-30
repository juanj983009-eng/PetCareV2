package com.petcare.enterprise.identity.application;

import java.util.List;
import java.util.UUID;

public interface RoleManagementUseCase {

    RoleCatalog catalog();

    ManagedRole get(UUID roleId);

    ManagedRole replacePermissions(UUID roleId, List<UUID> permissionIds);

    UserRoles replaceUserRoles(UUID userId, List<UUID> roleIds, long version);

    record Permission(UUID id, String code, String description, String module) {
    }

    record ManagedRole(UUID id, String name, String description, boolean system, List<Permission> permissions) {
    }

    record RoleCatalog(List<ManagedRole> roles, List<Permission> availablePermissions) {
    }

    record UserRoles(UUID userId, List<UUID> roleIds, long version) {
    }
}
