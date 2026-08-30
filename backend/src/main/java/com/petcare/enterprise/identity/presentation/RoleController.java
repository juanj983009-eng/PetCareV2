package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.RoleManagementUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/roles")
class RoleController {

    private final RoleManagementUseCase roles;

    RoleController(RoleManagementUseCase roles) {
        this.roles = roles;
    }

    @GetMapping
    RoleCatalogResponse catalog() {
        return RoleCatalogResponse.from(roles.catalog());
    }

    @GetMapping("/{roleId}")
    RoleResponse get(@PathVariable UUID roleId) {
        return RoleResponse.from(roles.get(roleId));
    }

    @PutMapping("/{roleId}/permissions")
    RoleResponse replacePermissions(
            @PathVariable UUID roleId,
            @Valid @RequestBody ReplaceRolePermissionsRequest request) {
        return RoleResponse.from(roles.replacePermissions(roleId, request.permissionIds()));
    }
}
