package com.petcare.enterprise.clinical.application;

import java.util.UUID;

public interface PatientRegistrationRepository {
    boolean activeOwnerExists(UUID tenantId, UUID ownerId);
    PatientRegistrationUseCase.Patient createPatientAndMedicalRecord(UUID tenantId, PatientRegistrationUseCase.NewPatient patient);
}
