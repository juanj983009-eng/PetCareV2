package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.AddDiagnosisCommand;

record CreateDiagnosisRequest(
        String description,
        String diagnosisType,
        String code,
        String notes) {

    AddDiagnosisCommand toCommand() {
        return new AddDiagnosisCommand(description, diagnosisType, code, notes);
    }
}
