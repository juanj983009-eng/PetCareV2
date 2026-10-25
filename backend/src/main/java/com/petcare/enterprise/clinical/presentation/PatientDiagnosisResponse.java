package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientDiagnosisSummary;
import java.time.Instant;
import java.util.UUID;

record PatientDiagnosisResponse(
        UUID id,
        UUID consultationId,
        Instant consultationStartedAt,
        String consultationReason,
        String code,
        String description,
        String diagnosisType,
        String notes,
        Instant createdAt) {

    static PatientDiagnosisResponse from(PatientDiagnosisSummary summary) {
        return new PatientDiagnosisResponse(
                summary.id(),
                summary.consultationId(),
                summary.consultationStartedAt(),
                summary.consultationReason(),
                summary.code(),
                summary.description(),
                summary.diagnosisType(),
                summary.notes(),
                summary.createdAt());
    }
}
