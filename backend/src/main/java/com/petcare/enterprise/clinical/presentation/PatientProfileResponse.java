package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.PatientProfileUseCase.MedicalRecordSummary;
import com.petcare.enterprise.clinical.application.PatientProfileUseCase.OwnerSummary;
import com.petcare.enterprise.clinical.application.PatientProfileUseCase.PatientProfile;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record PatientProfileResponse(
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
        OwnerSummaryResponse owner,
        MedicalRecordSummaryResponse medicalRecord) {

    static PatientProfileResponse from(PatientProfile profile) {
        return new PatientProfileResponse(
                profile.id(),
                profile.name(),
                profile.species(),
                profile.breed(),
                profile.sex(),
                profile.birthDate(),
                profile.color(),
                profile.microchip(),
                profile.sterilized(),
                profile.status(),
                profile.createdAt(),
                OwnerSummaryResponse.from(profile.owner()),
                MedicalRecordSummaryResponse.from(profile.medicalRecord()));
    }

    record OwnerSummaryResponse(
            UUID id,
            String fullName,
            String documentType,
            String documentNumber,
            String phone,
            String email) {

        static OwnerSummaryResponse from(OwnerSummary owner) {
            return new OwnerSummaryResponse(
                    owner.id(),
                    owner.fullName(),
                    owner.documentType(),
                    owner.documentNumber(),
                    owner.phone(),
                    owner.email());
        }
    }

    record MedicalRecordSummaryResponse(
            UUID id,
            String allergies,
            String medicalAlerts,
            String history,
            Instant createdAt) {

        static MedicalRecordSummaryResponse from(MedicalRecordSummary record) {
            if (record == null) {
                return null;
            }
            return new MedicalRecordSummaryResponse(
                    record.id(),
                    record.allergies(),
                    record.medicalAlerts(),
                    record.history(),
                    record.createdAt());
        }
    }
}
