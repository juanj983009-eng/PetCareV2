package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.ConsultationSummary;
import com.petcare.enterprise.clinical.domain.ClinicalDetails;
import com.petcare.enterprise.clinical.domain.VitalSigns;
import java.time.Instant;
import java.util.UUID;

record ConsultationResponse(
        UUID id,
        UUID appointmentId,
        String appointmentStatus,
        UUID patientId,
        String patientName,
        UUID medicalRecordId,
        UUID branchId,
        String branchName,
        UUID veterinarianId,
        String veterinarianFullName,
        String status,
        Instant startedAt,
        String reason,
        String anamnesis,
        String physicalExam,
        VitalSigns vitalSigns,
        ClinicalDetails clinicalDetails,
        Instant createdAt,
        Instant updatedAt) {

    static ConsultationResponse from(ConsultationSummary summary) {
        return new ConsultationResponse(
                summary.id(),
                summary.appointmentId(),
                summary.appointmentStatus(),
                summary.patientId(),
                summary.patientName(),
                summary.medicalRecordId(),
                summary.branchId(),
                summary.branchName(),
                summary.veterinarianId(),
                summary.veterinarianFullName(),
                summary.status(),
                summary.startedAt(),
                summary.reason(),
                summary.anamnesis(),
                summary.physicalExam(),
                summary.vitalSigns(),
                summary.clinicalDetails(),
                summary.createdAt(),
                summary.updatedAt());
    }
}
