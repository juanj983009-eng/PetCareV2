package com.petcare.enterprise.tenancy.infrastructure.persistence;

import com.petcare.enterprise.tenancy.application.BranchRepository;
import com.petcare.enterprise.tenancy.domain.Branch;
import java.util.List;
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
    public void saveForPlatformRegistration(Branch branch) {
        repository.saveAndFlush(BranchJpaEntity.from(branch));
    }

    @Override
    public Optional<Branch> findByTenantIdAndId(UUID tenantId, UUID branchId) {
        return repository.findByTenantIdAndId(tenantId, branchId).map(BranchJpaEntity::toDomain);
    }

    @Override
    public Optional<Branch> findByTenantIdAndName(UUID tenantId, String name) {
        return repository.findByTenantIdAndName(tenantId, name).map(BranchJpaEntity::toDomain);
    }

    @Override
    public List<Branch> findAllByTenantId(UUID tenantId) {
        return repository.findAllByTenantIdOrderByNameAsc(tenantId).stream()
                .map(BranchJpaEntity::toDomain)
                .toList();
    }
}
