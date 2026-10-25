package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.ConsultationSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.DiagnosisSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientDiagnosisSummary;
import com.petcare.enterprise.clinical.domain.ClinicalDetails;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultationRepository {

    Optional<ActivePatientMedicalRecord> findActivePatientMedicalRecord(UUID tenantId, UUID patientId);

    boolean patientExists(UUID tenantId, UUID patientId);

    Optional<ConsultationSummary> findById(UUID tenantId, UUID consultationId);

    Optional<ConsultationSummary> lockConsultationForUpdate(UUID tenantId, UUID consultationId);

    Optional<ConsultationSummary> findByAppointmentId(UUID tenantId, UUID appointmentId);

    ConsultationSummary insert(NewConsultationRecord record);

    ConsultationSummary updateEvaluation(UpdateEvaluationRecord record);

    List<DiagnosisSummary> findDiagnosesByConsultationId(UUID tenantId, UUID consultationId);

    Optional<DiagnosisSummary> findDiagnosisById(UUID tenantId, UUID consultationId, UUID diagnosisId);

    DiagnosisSummary insertDiagnosis(NewDiagnosisRecord record);

    DiagnosisSummary updateDiagnosis(UpdateDiagnosisRecord record);

    List<PatientDiagnosisSummary> findDiagnosesByPatientId(UUID tenantId, UUID patientId);

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

    record NewDiagnosisRecord(
            UUID id,
            UUID tenantId,
            UUID consultationId,
            String code,
            String description,
            String diagnosisType,
            String notes,
            Instant createdAt,
            Instant updatedAt) {
    }

    record UpdateDiagnosisRecord(
            UUID tenantId,
            UUID consultationId,
            UUID diagnosisId,
            String code,
            String description,
            String diagnosisType,
            String notes,
            Instant updatedAt) {
    }
}
