package com.petcare.enterprise.tenancy.infrastructure.persistence;

import com.petcare.enterprise.tenancy.application.BranchRepository;
import com.petcare.enterprise.tenancy.domain.Branch;
import com.petcare.enterprise.tenancy.domain.DomainValidationException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaBranchRepositoryAdapter implements BranchRepository {

    private final SpringDataBranchRepository repository;

    JpaBranchRepositoryAdapter(SpringDataBranchRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(UUID tenantId, Branch branch) {
        if (!branch.tenantId().equals(tenantId)) {
            throw new DomainValidationException("Branch does not belong to the requested tenant scope");
        }
        repository.saveAndFlush(BranchJpaEntity.from(branch));
    }

    @Override
    public Optional<Branch> findByTenantIdAndId(UUID tenantId, UUID branchId) {
        return repository.findByTenantIdAndId(tenantId, branchId).map(BranchJpaEntity::toDomain);
    }
}
