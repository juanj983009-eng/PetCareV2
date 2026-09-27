package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.PatientRegistrationUseCase;
import com.petcare.enterprise.clinical.application.PatientSearchUseCase;
import com.petcare.enterprise.clinical.application.PatientSearchUseCase.PatientSearch;
import com.petcare.enterprise.clinical.application.PatientSearchUseCase.SortDirection;
import com.petcare.enterprise.clinical.application.PatientSearchUseCase.SortField;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/patients")
class PatientController {
    private final PatientRegistrationUseCase patients;
    private final PatientSearchUseCase searchUseCase;

    PatientController(PatientRegistrationUseCase patients, PatientSearchUseCase searchUseCase) {
        this.patients = patients;
        this.searchUseCase = searchUseCase;
    }

    @GetMapping
    PatientPageResponse search(
            @RequestParam(required = false) @Size(max = 100) @Pattern(regexp = "^$|(?s).*\\S.*\\S.*") String query,
            @RequestParam(required = false) @Size(max = 50) @Pattern(regexp = "^$|(?s).*\\S.*") String species,
            @RequestParam(required = false) @Pattern(regexp = "^$|(?i)(?:ACTIVO|INACTIVO|FALLECIDO)") String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = "name|species|ownerName|status|createdAt") String sortBy,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "(?i)asc|desc") String direction) {
        return PatientPageResponse.from(searchUseCase.search(new PatientSearch(
                page,
                size,
                normalizeOptional(query),
                normalizeOptional(species),
                normalizeStatus(status),
                sortField(sortBy),
                sortDirection(direction))));
    }

    @PostMapping
    ResponseEntity<PatientResponse> create(@Valid @RequestBody CreatePatientRequest request) {
        var patient = patients.register(request.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/patients/" + patient.id()))
                .body(PatientResponse.from(patient));
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static SortField sortField(String value) {
        return switch (String.valueOf(value)) {
            case "name" -> SortField.NAME;
            case "species" -> SortField.SPECIES;
            case "ownerName" -> SortField.OWNER_NAME;
            case "status" -> SortField.STATUS;
            case "createdAt" -> SortField.CREATED_AT;
            default -> throw new IllegalArgumentException("Invalid patient sort");
        };
    }

    private static SortDirection sortDirection(String value) {
        return switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
            case "asc" -> SortDirection.ASC;
            case "desc" -> SortDirection.DESC;
            default -> throw new IllegalArgumentException("Invalid patient direction");
        };
    }
}
