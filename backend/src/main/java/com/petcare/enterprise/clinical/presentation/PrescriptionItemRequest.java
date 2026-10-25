package com.petcare.enterprise.clinical.presentation;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PrescriptionItemCommand;
import java.util.UUID;

public record PrescriptionItemRequest(
        String medicationName,
        String dosage,
        String frequency,
        String duration,
        String route,
        @JsonAlias("specificInstructions")
        String instructions,
        Boolean deductStock,
        UUID productId) {

    PrescriptionItemCommand toCommand() {
        return new PrescriptionItemCommand(
                medicationName,
                dosage,
                frequency,
                duration,
                route,
                instructions,
                deductStock,
                productId);
    }
}
