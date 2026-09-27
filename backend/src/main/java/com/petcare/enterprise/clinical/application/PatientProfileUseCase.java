package com.petcare.enterprise.clinical.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface PatientProfileUseCase {
    PatientProfile getById(UUID patientId);

    record PatientProfile(
            UUID id,
            String name,
            String species,
            String breed,
            String sex,
            LocalDate birthDate,
            String color,
            String microchip,
            Boolean sterilized,
            String status,
            Instant createdAt,
            OwnerSummary owner,
            MedicalRecordSummary medicalRecord) { }

    record OwnerSummary(
            UUID id,
            String fullName,
            String documentType,
            String documentNumber,
            String phone,
            String email) { }

    record MedicalRecordSummary(
            UUID id,
            String allergies,
            String medicalAlerts,
            String history,
            Instant createdAt) { }
}
