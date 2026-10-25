package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.StartConsultationResult;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ConsultationController {

    private final ConsultationUseCase consultations;

    ConsultationController(ConsultationUseCase consultations) {
        this.consultations = Objects.requireNonNull(consultations, "consultations is required");
    }

    @PostMapping("/api/v1/appointments/{appointmentId}/consultation")
    ResponseEntity<ConsultationResponse> startFromAppointment(
            @PathVariable("appointmentId") UUID appointmentId) {
        return toResponse(consultations.startFromAppointment(appointmentId));
    }

    @GetMapping("/api/v1/consultations/{consultationId}")
    ResponseEntity<ConsultationResponse> getById(
            @PathVariable("consultationId") UUID consultationId) {
        var summary = consultations.getById(consultationId);
        var diagList = consultations.listDiagnoses(consultationId).stream()
                .map(DiagnosisResponse::from)
                .toList();
        var prescList = consultations.listPrescriptions(consultationId).stream()
                .map(PrescriptionResponse::from)
                .toList();
        return ResponseEntity.ok(ConsultationResponse.from(summary, diagList, prescList));
    }

    @PutMapping("/api/v1/consultations/{consultationId}/evaluation")
    ResponseEntity<ConsultationResponse> saveEvaluation(
            @PathVariable("consultationId") UUID consultationId,
            @RequestBody SaveEvaluationRequest request) {
        return ResponseEntity.ok(ConsultationResponse.from(
                consultations.saveEvaluation(consultationId, request != null ? request.toCommand() : null)));
    }

    @GetMapping("/api/v1/consultations/{consultationId}/diagnoses")
    ResponseEntity<List<DiagnosisResponse>> listDiagnoses(
            @PathVariable("consultationId") UUID consultationId) {
        return ResponseEntity.ok(consultations.listDiagnoses(consultationId).stream()
                .map(DiagnosisResponse::from)
                .toList());
    }

    @PostMapping("/api/v1/consultations/{consultationId}/diagnoses")
    ResponseEntity<DiagnosisResponse> addDiagnosis(
            @PathVariable("consultationId") UUID consultationId,
            @RequestBody CreateDiagnosisRequest request) {
        var created = consultations.addDiagnosis(consultationId, request != null ? request.toCommand() : null);
        URI location = URI.create("/api/v1/consultations/" + consultationId + "/diagnoses/" + created.id());
        return ResponseEntity.created(location).body(DiagnosisResponse.from(created));
    }

    @PutMapping("/api/v1/consultations/{consultationId}/diagnoses/{diagnosisId}")
    ResponseEntity<DiagnosisResponse> updateDiagnosis(
            @PathVariable("consultationId") UUID consultationId,
            @PathVariable("diagnosisId") UUID diagnosisId,
            @RequestBody UpdateDiagnosisRequest request) {
        var updated = consultations.updateDiagnosis(consultationId, diagnosisId, request != null ? request.toCommand() : null);
        return ResponseEntity.ok(DiagnosisResponse.from(updated));
    }

    @GetMapping("/api/v1/patients/{patientId}/diagnoses")
    ResponseEntity<List<PatientDiagnosisResponse>> listPatientDiagnoses(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(consultations.listPatientDiagnoses(patientId).stream()
                .map(PatientDiagnosisResponse::from)
                .toList());
    }

    @GetMapping("/api/v1/consultations/{consultationId}/prescriptions")
    ResponseEntity<List<PrescriptionResponse>> listPrescriptions(
            @PathVariable("consultationId") UUID consultationId) {
        return ResponseEntity.ok(consultations.listPrescriptions(consultationId).stream()
                .map(PrescriptionResponse::from)
                .toList());
    }

    @PostMapping("/api/v1/consultations/{consultationId}/prescriptions")
    ResponseEntity<PrescriptionResponse> issuePrescription(
            @PathVariable("consultationId") UUID consultationId,
            @RequestBody IssuePrescriptionRequest request) {
        var created = consultations.issuePrescription(consultationId, request != null ? request.toCommand() : null);
        URI location = URI.create("/api/v1/consultations/" + consultationId + "/prescriptions/" + created.id());
        return ResponseEntity.created(location).body(PrescriptionResponse.from(created));
    }

    private static ResponseEntity<ConsultationResponse> toResponse(StartConsultationResult result) {
        URI location = URI.create("/api/v1/consultations/" + result.consultation().id());
        ConsultationResponse body = ConsultationResponse.from(result.consultation());
        if (result.created()) {
            return ResponseEntity.created(location).body(body);
        }
        return ResponseEntity.ok().location(location).body(body);
    }
}
