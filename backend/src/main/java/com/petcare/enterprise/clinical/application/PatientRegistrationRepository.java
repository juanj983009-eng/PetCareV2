package com.petcare.enterprise.clinical.application;

import java.util.Optional;
import java.util.UUID;

public interface PatientRegistrationRepository {
    boolean activeOwnerExists(UUID tenantId, UUID ownerId);
    Optional<PatientRegistrationUseCase.Patient> findById(UUID tenantId, UUID patientId);
    boolean microchipExistsExcluding(UUID tenantId, String microchip, UUID excludedPatientId);
    PatientRegistrationUseCase.Patient createPatientAndMedicalRecord(UUID tenantId, PatientRegistrationUseCase.NewPatient patient);
    PatientRegistrationUseCase.Patient update(UUID tenantId, UUID patientId, PatientRegistrationUseCase.NewPatient patient);
}
