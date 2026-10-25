package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.DiagnosisSummary;
import java.time.Instant;
import java.util.UUID;

record DiagnosisResponse(
        UUID id,
        UUID consultationId,
        String code,
        String description,
        String diagnosisType,
        String notes,
        Instant createdAt,
        Instant updatedAt) {

    static DiagnosisResponse from(DiagnosisSummary summary) {
        return new DiagnosisResponse(
                summary.id(),
                summary.consultationId(),
                summary.code(),
                summary.description(),
                summary.diagnosisType(),
                summary.notes(),
                summary.createdAt(),
                summary.updatedAt());
    }
}
