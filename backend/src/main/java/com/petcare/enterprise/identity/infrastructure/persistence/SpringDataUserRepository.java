package com.petcare.enterprise.identity.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataUserRepository extends JpaRepository<UserJpaEntity, UUID> {

    Optional<UserJpaEntity> findByTenantIdAndEmail(UUID tenantId, String email);

    Optional<UserJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    @Query(value = """
            SELECT DISTINCT r.name
            FROM user_roles ur
            JOIN roles r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
            WHERE ur.tenant_id = :tenantId AND ur.user_id = :userId
            ORDER BY r.name
            """, nativeQuery = true)
    List<String> findRoleNames(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    @Query(value = """
            SELECT ub.branch_id
            FROM user_branches ub
            WHERE ub.tenant_id = :tenantId
              AND ub.user_id = :userId
              AND ub.primary_branch = true
            """, nativeQuery = true)
    Optional<UUID> findPrimaryBranchId(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    @Query(value = """
            SELECT DISTINCT p.code
            FROM user_roles ur
            JOIN roles r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
            JOIN role_permissions rp ON rp.tenant_id = r.tenant_id AND rp.role_id = r.id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE ur.tenant_id = :tenantId AND ur.user_id = :userId
            ORDER BY p.code
            """, nativeQuery = true)
    List<String> findEffectiveAuthorities(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserJpaEntity u
               SET u.lastAccessAt = :lastAccessAt
             WHERE u.tenantId = :tenantId AND u.id = :userId
            """)
    int updateLastAccess(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId,
            @Param("lastAccessAt") Instant lastAccessAt);
}
