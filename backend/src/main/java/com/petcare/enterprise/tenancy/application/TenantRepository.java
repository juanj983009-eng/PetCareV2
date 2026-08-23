package com.petcare.enterprise.tenancy.application;

import com.petcare.enterprise.tenancy.domain.Tenant;

public interface TenantRepository {

    /** Global platform operation required before a tenant root exists. */
    boolean existsBySlug(String slug);

    /** Global platform operation required to create a tenant root. */
    void save(Tenant tenant);
}
