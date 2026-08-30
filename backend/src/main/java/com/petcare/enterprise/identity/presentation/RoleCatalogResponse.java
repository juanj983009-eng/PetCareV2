package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.RoleManagementUseCase.RoleCatalog;
import java.util.List;

record RoleCatalogResponse(List<RoleResponse> roles, List<PermissionResponse> availablePermissions) {

    static RoleCatalogResponse from(RoleCatalog catalog) {
        return new RoleCatalogResponse(
                catalog.roles().stream().map(RoleResponse::from).toList(),
                catalog.availablePermissions().stream().map(PermissionResponse::from).toList());
    }
}
