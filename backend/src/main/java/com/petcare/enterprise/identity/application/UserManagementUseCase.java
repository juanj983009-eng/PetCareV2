package com.petcare.enterprise.identity.application;

import com.petcare.enterprise.identity.domain.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UserManagementUseCase {

    UserPage list(UserSearch search);

    ManagedUser get(UUID userId);

    UserOptions options();

    ManagedUser create(CreateUser command);

    ManagedUser update(UUID userId, UpdateUser command);

    ManagedUser activate(UUID userId, long version);

    ManagedUser deactivate(UUID userId, long version);

    record UserSearch(
            int page,
            int size,
            String search,
            UUID roleId,
            UserStatus status,
            SortField sortField,
            SortDirection direction) {
    }

    enum SortField {
        FIRST_NAME,
        LAST_NAME,
        EMAIL,
        STATUS,
        CREATED_AT
    }

    enum SortDirection {
        ASC,
        DESC
    }

    record CreateUser(
            String firstName,
            String lastName,
            String email,
            String initialPassword,
            UUID roleId,
            UUID branchId) {
    }

    record UpdateUser(
            String firstName,
            String lastName,
            String email,
            UUID roleId,
            UUID branchId,
            long version) {
    }

    record RoleOption(UUID id, String name) {
    }

    record BranchOption(UUID id, String name, boolean active) {
    }

    record ManagedUser(
            UUID id,
            String firstName,
            String lastName,
            String email,
            UserStatus status,
            RoleOption role,
            BranchOption branch,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    record UserPage(
            List<ManagedUser> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    record UserOptions(List<RoleOption> roles, List<BranchOption> branches) {
    }
}
