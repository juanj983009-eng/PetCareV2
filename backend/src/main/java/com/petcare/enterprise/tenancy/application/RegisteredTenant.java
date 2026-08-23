package com.petcare.enterprise.tenancy.application;

import com.petcare.enterprise.tenancy.domain.TenantPlan;
import com.petcare.enterprise.tenancy.domain.TenantStatus;
import java.time.Instant;
import java.util.UUID;

public record RegisteredTenant(
        UUID id,
        String name,
        String ruc,
        String slug,
        TenantPlan plan,
        TenantStatus status,
        Instant createdAt,
        RegisteredBranch mainBranch) {

    public record RegisteredBranch(
            UUID id,
            String name,
            String address,
            String phone,
            String timeZone,
            boolean active,
            Instant createdAt) {
    }
}
