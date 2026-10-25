package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.SaveEvaluationCommand;
import com.petcare.enterprise.clinical.domain.VitalSigns;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

record SaveEvaluationRequest(
        @NotBlank(message = "El motivo de la consulta es obligatorio.")
        String reason,
        String anamnesis,
        String physicalExam,
        VitalSigns vitalSigns,
        String observations,
        Map<String, String> variableDetails) {

    SaveEvaluationCommand toCommand() {
        return new SaveEvaluationCommand(
                reason,
                anamnesis,
                physicalExam,
                vitalSigns,
                observations,
                variableDetails);
    }
}
