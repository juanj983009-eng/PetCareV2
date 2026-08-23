package com.petcare.enterprise.tenancy.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted server identity, which may represent either a tenant user or a global platform user. */
public record AuthenticatedIdentity(UUID userId, UUID tenantId, UUID branchId) {

    public AuthenticatedIdentity {
        Objects.requireNonNull(userId, "User id is required");
        if (branchId != null && tenantId == null) {
            throw new IllegalArgumentException("A branch requires a tenant");
        }
    }

    public Optional<UUID> optionalTenantId() {
        return Optional.ofNullable(tenantId);
    }

    public Optional<UUID> optionalBranchId() {
        return Optional.ofNullable(branchId);
    }
}
