package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.clinical.application.PatientProfileUseCase.PatientProfile;
import java.util.Optional;
import java.util.UUID;

public interface PatientProfileRepository {
    Optional<PatientProfile> findById(UUID tenantId, UUID patientId);
}
