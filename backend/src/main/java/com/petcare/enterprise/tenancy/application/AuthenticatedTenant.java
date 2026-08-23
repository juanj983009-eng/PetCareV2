package com.petcare.enterprise.tenancy.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AuthenticatedTenant(UUID tenantId, UUID userId, UUID branchId) {

    public AuthenticatedTenant {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(userId, "User id is required");
    }

    public Optional<UUID> optionalBranchId() {
        return Optional.ofNullable(branchId);
    }
}
