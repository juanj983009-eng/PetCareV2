package com.petcare.enterprise.audit.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AuditMetadata(UUID tenantId, UUID actorUserId, UUID branchId, UUID correlationId) {

    public AuditMetadata {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(actorUserId, "Actor user id is required");
        Objects.requireNonNull(correlationId, "Correlation id is required");
    }

    public Optional<UUID> optionalBranchId() {
        return Optional.ofNullable(branchId);
    }
}
