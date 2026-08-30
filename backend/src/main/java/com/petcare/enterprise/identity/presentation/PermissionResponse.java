package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.RoleManagementUseCase.Permission;
import java.util.UUID;

record PermissionResponse(UUID id, String code, String description, String module) {

    static PermissionResponse from(Permission permission) {
        return new PermissionResponse(permission.id(), permission.code(), permission.description(), permission.module());
    }
}
