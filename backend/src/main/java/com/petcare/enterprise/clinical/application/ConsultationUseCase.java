package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.clinical.domain.ClinicalDetails;
import com.petcare.enterprise.clinical.domain.VitalSigns;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface ConsultationUseCase {

    StartConsultationResult startFromAppointment(UUID appointmentId);

    ConsultationSummary getById(UUID consultationId);

    ConsultationSummary saveEvaluation(UUID consultationId, SaveEvaluationCommand command);

    record StartConsultationResult(
            ConsultationSummary consultation,
            boolean created) {
    }

    record SaveEvaluationCommand(
            String reason,
            String anamnesis,
            String physicalExam,
            VitalSigns vitalSigns,
            String observations,
            Map<String, String> variableDetails) {
    }

    record ConsultationSummary(
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
    }
}
