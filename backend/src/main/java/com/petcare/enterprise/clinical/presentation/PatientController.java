package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.PatientRegistrationUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/v1/patients")
class PatientController {
    private final PatientRegistrationUseCase patients;
    PatientController(PatientRegistrationUseCase patients) { this.patients=patients; }
    @PostMapping ResponseEntity<PatientResponse> create(@Valid @RequestBody CreatePatientRequest request) { var patient=patients.register(request.toCommand()); return ResponseEntity.created(URI.create("/api/v1/patients/"+patient.id())).body(PatientResponse.from(patient)); }
}
