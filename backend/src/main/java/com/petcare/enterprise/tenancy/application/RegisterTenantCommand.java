package com.petcare.enterprise.tenancy.application;

import com.petcare.enterprise.tenancy.domain.TenantPlan;
import com.petcare.enterprise.tenancy.domain.TenantStatus;
import java.util.UUID;

public record RegisterTenantCommand(
        String name,
        String ruc,
        String slug,
        TenantPlan plan,
        TenantStatus status,
        String mainBranchName,
        String mainBranchAddress,
        String mainBranchPhone,
        String mainBranchTimeZone,
        UUID correlationId) {
}
