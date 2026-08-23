package com.petcare.enterprise.tenancy.application;

import com.petcare.enterprise.tenancy.domain.Tenant;
import java.util.Optional;

public interface TenantRepository {

    /** Global platform operation required before a tenant root exists. */
    boolean existsBySlug(String slug);

    /** Global platform operation required to create a tenant root. */
    void save(Tenant tenant);

    /** Trusted identity lookup. The client never supplies the resulting tenant id. */
    Optional<Tenant> findBySlugForAuthentication(String slug);
}
