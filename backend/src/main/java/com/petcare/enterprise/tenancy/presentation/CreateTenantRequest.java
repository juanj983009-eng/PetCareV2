package com.petcare.enterprise.tenancy.presentation;

import com.petcare.enterprise.tenancy.domain.TenantPlan;
import com.petcare.enterprise.tenancy.domain.TenantStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTenantRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 20) String ruc,
        @NotBlank @Size(min = 3, max = 80)
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
        @NotNull TenantPlan plan,
        @NotNull TenantStatus status,
        @NotNull @Valid MainBranchRequest mainBranch) {

    public record MainBranchRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 250) String address,
            @Size(max = 30) String phone,
            @NotBlank @Size(max = 50) String timeZone) {
    }
}
