package com.petcare.enterprise.tenancy.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataBranchRepository extends JpaRepository<BranchJpaEntity, UUID> {

    Optional<BranchJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<BranchJpaEntity> findByTenantIdAndName(UUID tenantId, String name);

    List<BranchJpaEntity> findAllByTenantIdOrderByNameAsc(UUID tenantId);
}
