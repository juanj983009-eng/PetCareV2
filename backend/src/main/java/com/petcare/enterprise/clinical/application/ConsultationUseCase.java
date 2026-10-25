package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.clinical.domain.ClinicalDetails;
import com.petcare.enterprise.clinical.domain.VitalSigns;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ConsultationUseCase {

    StartConsultationResult startFromAppointment(UUID appointmentId);

    ConsultationSummary getById(UUID consultationId);

    ConsultationSummary saveEvaluation(UUID consultationId, SaveEvaluationCommand command);

    List<DiagnosisSummary> listDiagnoses(UUID consultationId);

    DiagnosisSummary addDiagnosis(UUID consultationId, AddDiagnosisCommand command);

    DiagnosisSummary updateDiagnosis(UUID consultationId, UUID diagnosisId, UpdateDiagnosisCommand command);

    List<PatientDiagnosisSummary> listPatientDiagnoses(UUID patientId);

    List<PrescriptionSummary> listPrescriptions(UUID consultationId);

    PrescriptionSummary issuePrescription(UUID consultationId, IssuePrescriptionCommand command);

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

    record AddDiagnosisCommand(
            String description,
            String diagnosisType,
            String code,
            String notes) {
    }

    record UpdateDiagnosisCommand(
            String description,
            String diagnosisType,
            String code,
            String notes) {
    }

    record DiagnosisSummary(
            UUID id,
            UUID consultationId,
            String code,
            String description,
            String diagnosisType,
            String notes,
            Instant createdAt,
            Instant updatedAt) {
    }

    record PatientDiagnosisSummary(
            UUID id,
            UUID consultationId,
            Instant consultationStartedAt,
            String consultationReason,
            String code,
            String description,
            String diagnosisType,
            String notes,
            Instant createdAt) {
    }

    record IssuePrescriptionCommand(
            String generalInstructions,
            List<PrescriptionItemCommand> items,
            String idempotencyKey,
            Boolean deductStock,
            UUID productId) {
    }

    record PrescriptionItemCommand(
            String medicationName,
            String dosage,
            String frequency,
            String duration,
            String route,
            String instructions,
            Boolean deductStock,
            UUID productId) {
    }

    record PrescriptionSummary(
            UUID id,
            UUID consultationId,
            UUID prescribedBy,
            String veterinarianFullName,
            Instant issuedAt,
            String generalInstructions,
            String status,
            List<PrescriptionItemSummary> items,
            Instant createdAt) {
    }

    record PrescriptionItemSummary(
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
