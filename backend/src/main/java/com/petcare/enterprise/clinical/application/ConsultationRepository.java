package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.ConsultationSummary;
import com.petcare.enterprise.clinical.domain.ClinicalDetails;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ConsultationRepository {

    Optional<ActivePatientMedicalRecord> findActivePatientMedicalRecord(UUID tenantId, UUID patientId);

    Optional<ConsultationSummary> findById(UUID tenantId, UUID consultationId);

    Optional<ConsultationSummary> findByAppointmentId(UUID tenantId, UUID appointmentId);

    ConsultationSummary insert(NewConsultationRecord record);

    ConsultationSummary updateEvaluation(UpdateEvaluationRecord record);

    record ActivePatientMedicalRecord(
            UUID patientId,
            String patientName,
            UUID medicalRecordId) {
    }

    record NewConsultationRecord(
            UUID id,
            UUID tenantId,
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
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    record UpdateEvaluationRecord(
            UUID tenantId,
            UUID consultationId,
            String reason,
            String anamnesis,
            String physicalExam,
            ClinicalDetails clinicalDetails,
            Instant updatedAt) {
    }
}
