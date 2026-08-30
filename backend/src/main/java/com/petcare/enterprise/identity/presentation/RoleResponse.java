package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.RoleManagementUseCase.ManagedRole;
import java.util.List;
import java.util.UUID;

record RoleResponse(UUID id, String name, String description, boolean system, List<PermissionResponse> permissions) {

    static RoleResponse from(ManagedRole role) {
        return new RoleResponse(role.id(), role.name(), role.description(), role.system(),
                role.permissions().stream().map(PermissionResponse::from).toList());
    }
}
