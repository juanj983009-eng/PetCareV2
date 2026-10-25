package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientConsultationDiagnosisSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientConsultationItemSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientConsultationPage;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientConsultationPrescriptionItemSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientConsultationPrescriptionSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record PatientConsultationPageResponse(
        List<PatientConsultationItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static PatientConsultationPageResponse from(PatientConsultationPage page) {
        return new PatientConsultationPageResponse(
                page.items().stream().map(PatientConsultationItemResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }

    record PatientConsultationItemResponse(
            UUID id,
            UUID appointmentId,
            Instant startedAt,
            String status,
            String reason,
            UUID veterinarianId,
            String veterinarianFullName,
            UUID branchId,
            String branchName,
            List<PatientConsultationDiagnosisResponse> diagnoses,
            List<PatientConsultationPrescriptionResponse> prescriptions) {

        static PatientConsultationItemResponse from(PatientConsultationItemSummary item) {
            return new PatientConsultationItemResponse(
                    item.id(),
                    item.appointmentId(),
                    item.startedAt(),
                    item.status(),
                    item.reason(),
                    item.veterinarianId(),
                    item.veterinarianFullName(),
                    item.branchId(),
                    item.branchName(),
                    item.diagnoses().stream().map(PatientConsultationDiagnosisResponse::from).toList(),
                    item.prescriptions().stream().map(PatientConsultationPrescriptionResponse::from).toList());
        }
    }

    record PatientConsultationDiagnosisResponse(
            UUID id,
            String code,
            String description,
            String diagnosisType) {

        static PatientConsultationDiagnosisResponse from(PatientConsultationDiagnosisSummary summary) {
            return new PatientConsultationDiagnosisResponse(
                    summary.id(),
                    summary.code(),
                    summary.description(),
                    summary.diagnosisType());
        }
    }

    record PatientConsultationPrescriptionResponse(
            UUID id,
            String status,
            Instant issuedAt,
            List<PatientConsultationPrescriptionItemResponse> items) {

        static PatientConsultationPrescriptionResponse from(PatientConsultationPrescriptionSummary summary) {
            return new PatientConsultationPrescriptionResponse(
                    summary.id(),
                    summary.status(),
                    summary.issuedAt(),
                    summary.items().stream().map(PatientConsultationPrescriptionItemResponse::from).toList());
        }
    }

    record PatientConsultationPrescriptionItemResponse(
            UUID id,
            String medicationName,
            String dosage,
            String frequency,
            String duration,
            String route) {

        static PatientConsultationPrescriptionItemResponse from(PatientConsultationPrescriptionItemSummary item) {
            return new PatientConsultationPrescriptionItemResponse(
                    item.id(),
                    item.medicationName(),
                    item.dosage(),
                    item.frequency(),
                    item.duration(),
                    item.route());
        }
    }
}
