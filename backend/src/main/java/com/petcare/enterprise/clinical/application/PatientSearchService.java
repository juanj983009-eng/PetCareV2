package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.tenancy.application.TenantContext;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientSearchService implements PatientSearchUseCase {
    private final TenantContext tenantContext;
    private final PatientSearchRepository patients;

    public PatientSearchService(TenantContext tenantContext, PatientSearchRepository patients) {
        this.tenantContext = tenantContext;
        this.patients = patients;
    }

    @Override
    @Transactional(readOnly = true)
    public PatientPage search(PatientSearch query) {
        Objects.requireNonNull(query, "Search query is required");
        return patients.search(tenantContext.requireTenant().tenantId(), query);
    }
}
