package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.tenancy.application.TenantContext;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OwnerSearchService implements OwnerSearchUseCase {
    private final TenantContext tenantContext;
    private final OwnerSearchRepository owners;

    public OwnerSearchService(TenantContext tenantContext, OwnerSearchRepository owners) {
        this.tenantContext = tenantContext;
        this.owners = owners;
    }

    @Override
    @Transactional(readOnly = true)
    public OwnerPage search(OwnerSearch query) {
        Objects.requireNonNull(query, "Search is required");
        return owners.search(tenantContext.requireTenant().tenantId(), query);
    }
}
