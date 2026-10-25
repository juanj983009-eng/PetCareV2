package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.UpdateDiagnosisCommand;

record UpdateDiagnosisRequest(
        String description,
        String diagnosisType,
        String code,
        String notes) {

    UpdateDiagnosisCommand toCommand() {
        return new UpdateDiagnosisCommand(description, diagnosisType, code, notes);
    }
}
