package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.PrescriptionItemSummary;
import java.time.Instant;
import java.util.UUID;

public record PrescriptionItemResponse(
        UUID id,
        UUID prescriptionId,
        int itemOrder,
        String medicationName,
        String dosage,
        String frequency,
        String duration,
        String route,
        String instructions,
        Instant createdAt) {

    public static PrescriptionItemResponse from(PrescriptionItemSummary summary) {
        return new PrescriptionItemResponse(
                summary.id(),
                summary.prescriptionId(),
                summary.itemOrder(),
                summary.medicationName(),
                summary.dosage(),
                summary.frequency(),
                summary.duration(),
                summary.route(),
                summary.instructions(),
                summary.createdAt());
    }
}
