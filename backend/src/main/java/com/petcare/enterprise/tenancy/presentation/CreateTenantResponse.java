package com.petcare.enterprise.tenancy.presentation;

import com.petcare.enterprise.tenancy.application.RegisteredTenant;
import com.petcare.enterprise.tenancy.domain.TenantPlan;
import com.petcare.enterprise.tenancy.domain.TenantStatus;
import java.time.Instant;
import java.util.UUID;

public record CreateTenantResponse(
        UUID id,
        String name,
        String ruc,
        String slug,
        TenantPlan plan,
        TenantStatus status,
        Instant createdAt,
        BranchResponse mainBranch) {

    static CreateTenantResponse from(RegisteredTenant tenant) {
        RegisteredTenant.RegisteredBranch branch = tenant.mainBranch();
        return new CreateTenantResponse(
                tenant.id(),
                tenant.name(),
                tenant.ruc(),
                tenant.slug(),
                tenant.plan(),
                tenant.status(),
                tenant.createdAt(),
                new BranchResponse(
                        branch.id(),
                        branch.name(),
                        branch.address(),
                        branch.phone(),
                        branch.timeZone(),
                        branch.active(),
                        branch.createdAt()));
    }

    public record BranchResponse(
            UUID id,
            String name,
            String address,
            String phone,
            String timeZone,
            boolean active,
            Instant createdAt) {
    }
}
