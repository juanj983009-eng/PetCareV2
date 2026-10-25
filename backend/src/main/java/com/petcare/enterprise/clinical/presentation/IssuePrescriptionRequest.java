package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.IssuePrescriptionCommand;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PrescriptionItemCommand;
import java.util.List;
import java.util.UUID;

public record IssuePrescriptionRequest(
        String generalInstructions,
        List<PrescriptionItemRequest> items,
        String idempotencyKey,
        Boolean deductStock,
        UUID productId) {

    IssuePrescriptionCommand toCommand() {
        List<PrescriptionItemCommand> itemCommands = items != null
                ? items.stream().map(it -> it != null ? it.toCommand() : null).toList()
                : List.of();
        return new IssuePrescriptionCommand(
                generalInstructions,
                itemCommands,
                idempotencyKey,
                deductStock,
                productId);
    }
}
