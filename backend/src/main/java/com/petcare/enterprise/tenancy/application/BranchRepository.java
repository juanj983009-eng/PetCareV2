package com.petcare.enterprise.tenancy.application;

import com.petcare.enterprise.tenancy.domain.Branch;
import java.util.Optional;
import java.util.UUID;

public interface BranchRepository {

    /** Global platform operation used only while creating a new tenant root. */
    void saveForPlatformRegistration(Branch branch);

    /** Tenant-bound lookup. A missing or foreign branch produces the same empty result. */
    Optional<Branch> findByTenantIdAndId(UUID tenantId, UUID branchId);
}
