package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationUseCase;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.StartConsultationResult;
import java.net.URI;
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
        return ResponseEntity.ok(ConsultationResponse.from(consultations.getById(consultationId)));
    }

    @PutMapping("/api/v1/consultations/{consultationId}/evaluation")
    ResponseEntity<ConsultationResponse> saveEvaluation(
            @PathVariable("consultationId") UUID consultationId,
            @RequestBody SaveEvaluationRequest request) {
        return ResponseEntity.ok(ConsultationResponse.from(
                consultations.saveEvaluation(consultationId, request != null ? request.toCommand() : null)));
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
