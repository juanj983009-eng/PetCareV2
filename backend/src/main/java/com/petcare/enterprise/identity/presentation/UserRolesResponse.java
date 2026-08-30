package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.RoleManagementUseCase.UserRoles;
import java.util.List;
import java.util.UUID;

record UserRolesResponse(UUID userId, List<UUID> roleIds, long version) {

    static UserRolesResponse from(UserRoles roles) {
        return new UserRolesResponse(roles.userId(), roles.roleIds(), roles.version());
    }
}
