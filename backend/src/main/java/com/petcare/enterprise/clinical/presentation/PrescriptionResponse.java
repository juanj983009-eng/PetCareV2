package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.PrescriptionSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PrescriptionResponse(
        UUID id,
        UUID consultationId,
        UUID prescribedBy,
        String veterinarianFullName,
        Instant issuedAt,
        String generalInstructions,
        String status,
        List<PrescriptionItemResponse> items,
        Instant createdAt) {

    public static PrescriptionResponse from(PrescriptionSummary summary) {
        List<PrescriptionItemResponse> itemResponses = summary.items() != null
                ? summary.items().stream().map(PrescriptionItemResponse::from).toList()
                : List.of();
        return new PrescriptionResponse(
                summary.id(),
                summary.consultationId(),
                summary.prescribedBy(),
                summary.veterinarianFullName(),
                summary.issuedAt(),
                summary.generalInstructions(),
                summary.status(),
                itemResponses,
                summary.createdAt());
    }
}
