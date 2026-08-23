package com.petcare.enterprise.tenancy.infrastructure.persistence;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTenantRepository extends JpaRepository<TenantJpaEntity, UUID> {

    boolean existsBySlug(String slug);

    Optional<TenantJpaEntity> findBySlug(String slug);
}
