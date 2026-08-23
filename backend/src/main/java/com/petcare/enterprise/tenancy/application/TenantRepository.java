package com.petcare.enterprise.tenancy.application;

import com.petcare.enterprise.tenancy.domain.Tenant;

public interface TenantRepository {

    boolean existsBySlug(String slug);

    void save(Tenant tenant);
}
