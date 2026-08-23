package com.petcare.enterprise.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataRoleRepository extends JpaRepository<RoleJpaEntity, UUID> {

    @Query("SELECT r FROM RoleJpaEntity r WHERE r.tenantId = :tenantId AND lower(r.name) = lower(:name)")
    Optional<RoleJpaEntity> findByTenantIdAndNameIgnoreCase(
            @Param("tenantId") UUID tenantId,
            @Param("name") String name);
}
