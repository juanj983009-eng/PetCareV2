package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.UserManagementUseCase.UserOptions;
import java.util.List;
import java.util.UUID;

record UserOptionsResponse(List<RoleOptionResponse> roles, List<BranchOptionResponse> branches) {

    static UserOptionsResponse from(UserOptions options) {
        return new UserOptionsResponse(
                options.roles().stream()
                        .map(role -> new RoleOptionResponse(role.id(), role.name()))
                        .toList(),
                options.branches().stream()
                        .map(branch -> new BranchOptionResponse(branch.id(), branch.name(), branch.active()))
                        .toList());
    }

    record RoleOptionResponse(UUID id, String name) {
    }

    record BranchOptionResponse(UUID id, String name, boolean active) {
    }
}
