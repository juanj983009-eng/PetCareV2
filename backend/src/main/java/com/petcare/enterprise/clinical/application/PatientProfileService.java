package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.tenancy.application.TenantContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientProfileService implements PatientProfileUseCase {
    private final TenantContext tenantContext;
    private final PatientProfileRepository patients;

    public PatientProfileService(TenantContext tenantContext, PatientProfileRepository patients) {
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext is required");
        this.patients = Objects.requireNonNull(patients, "patients is required");
    }

    @Override
    @Transactional(readOnly = true)
    public PatientProfile getById(UUID patientId) {
        Objects.requireNonNull(patientId, "patientId is required");
        UUID tenantId = tenantContext.requireTenant().tenantId();
        return patients.findById(tenantId, patientId)
                .orElseThrow(PatientNotFoundException::new);
    }
}
