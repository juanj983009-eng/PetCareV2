package com.petcare.enterprise.tenancy.application;

import com.petcare.enterprise.tenancy.domain.Branch;
import java.util.Optional;
import java.util.UUID;

public interface BranchRepository {

    void save(UUID tenantId, Branch branch);

    Optional<Branch> findByTenantIdAndId(UUID tenantId, UUID branchId);
}
