package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.UserManagementUseCase.BranchOption;
import com.petcare.enterprise.identity.application.UserManagementUseCase.ManagedUser;
import com.petcare.enterprise.identity.application.UserManagementUseCase.RoleOption;
import java.time.Instant;
import java.util.UUID;

record UserResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String status,
        RoleResponse role,
        BranchResponse branch,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    static UserResponse from(ManagedUser user) {
        return new UserResponse(
                user.id(),
                user.firstName(),
                user.lastName(),
                user.email(),
                user.status().name(),
                RoleResponse.from(user.role()),
                BranchResponse.from(user.branch()),
                user.createdAt(),
                user.updatedAt(),
                user.version());
    }

    record RoleResponse(UUID id, String name) {
        static RoleResponse from(RoleOption role) {
            return role == null ? null : new RoleResponse(role.id(), role.name());
        }
    }

    record BranchResponse(UUID id, String name, boolean active) {
        static BranchResponse from(BranchOption branch) {
            return branch == null ? null : new BranchResponse(branch.id(), branch.name(), branch.active());
        }
    }
}
