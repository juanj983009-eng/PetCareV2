package com.petcare.enterprise.identity.application;

import com.petcare.enterprise.identity.domain.EmailAddress;
import java.time.Instant;
import java.util.UUID;

public record BootstrapIdentity(
        UUID tenantId,
        UUID branchId,
        String firstName,
        String lastName,
        EmailAddress email,
        String passwordHash,
        Instant createdAt) {
}
