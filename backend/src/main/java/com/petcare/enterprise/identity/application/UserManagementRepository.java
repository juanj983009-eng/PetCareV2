package com.petcare.enterprise.identity.application;

import com.petcare.enterprise.identity.application.UserManagementUseCase.ManagedUser;
import com.petcare.enterprise.identity.application.UserManagementUseCase.RoleOption;
import com.petcare.enterprise.identity.application.UserManagementUseCase.UserPage;
import com.petcare.enterprise.identity.application.UserManagementUseCase.UserSearch;
import com.petcare.enterprise.identity.domain.EmailAddress;
import com.petcare.enterprise.identity.domain.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserManagementRepository {

    UserPage findAll(UUID tenantId, UserSearch search);

    Optional<ManagedUser> findByTenantIdAndId(UUID tenantId, UUID userId);

    Optional<RoleOption> findRoleByTenantIdAndId(UUID tenantId, UUID roleId);

    List<RoleOption> findRolesByTenantId(UUID tenantId);

    boolean existsByTenantIdAndEmail(UUID tenantId, EmailAddress email, UUID excludingUserId);

    ManagedUser create(NewUser user);

    Optional<ManagedUser> update(UpdatedUser user);

    Optional<ManagedUser> updateStatus(
            UUID tenantId,
            UUID userId,
            UserStatus status,
            long version,
            Instant updatedAt);

    record NewUser(
            UUID id,
            UUID tenantId,
            String firstName,
            String lastName,
            EmailAddress email,
            String passwordHash,
            UUID roleId,
            UUID branchId,
            Instant createdAt) {
    }

    record UpdatedUser(
            UUID id,
            UUID tenantId,
            String firstName,
            String lastName,
            EmailAddress email,
            UUID roleId,
            UUID branchId,
            long version,
            Instant updatedAt) {
    }
}
