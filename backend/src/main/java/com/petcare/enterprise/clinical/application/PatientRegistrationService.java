package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditMetadata;
import com.petcare.enterprise.audit.application.AuditMetadataProvider;
import com.petcare.enterprise.audit.application.AuditRecord;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientRegistrationService implements PatientRegistrationUseCase {
    private final PatientRegistrationRepository patients;
    private final AuditMetadataProvider audit;
    private final AuditLogWriter writer;
    private final Clock clock;

    public PatientRegistrationService(
            PatientRegistrationRepository patients,
            AuditMetadataProvider audit,
            AuditLogWriter writer,
            Clock clock) {
        this.patients = patients;
        this.audit = audit;
        this.writer = writer;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Patient register(NewPatient input) {
        AuditMetadata metadata = audit.requireTenantMetadata();
        NewPatient patient = normalize(input);
        if (!patients.activeOwnerExists(metadata.tenantId(), patient.ownerId())) {
            throw PatientRegistrationException.ownerUnavailable();
        }
        if (patient.microchip() != null && patients.microchipExistsExcluding(metadata.tenantId(), patient.microchip(), null)) {
            throw PatientRegistrationException.microchipConflict();
        }
        Patient created;
        try {
            created = patients.createPatientAndMedicalRecord(metadata.tenantId(), patient);
        } catch (DataIntegrityViolationException exception) {
            throw PatientRegistrationException.microchipConflict();
        }
        writer.append(new AuditRecord(
                UUID.randomUUID(),
                metadata.tenantId(),
                metadata.actorUserId(),
                metadata.branchId(),
                "PATIENT",
                created.id(),
                "PATIENT_CREATED",
                clock.instant(),
                null,
                metadata.correlationId(),
                null,
                Map.of(
                        "ownerId", created.ownerId().toString(),
                        "name", created.name(),
                        "species", created.species(),
                        "sex", created.sex())));
        return created;
    }

    @Override
    @Transactional
    public Patient update(UUID patientId, NewPatient input) {
        Objects.requireNonNull(patientId, "patientId is required");
        AuditMetadata metadata = audit.requireTenantMetadata();
        Patient current = patients.findById(metadata.tenantId(), patientId)
                .orElseThrow(PatientNotFoundException::new);
        NewPatient patient = normalize(input);
        if (!patients.activeOwnerExists(metadata.tenantId(), patient.ownerId())) {
            throw PatientRegistrationException.ownerUnavailable();
        }
        if (patient.microchip() != null
                && patients.microchipExistsExcluding(metadata.tenantId(), patient.microchip(), patientId)) {
            throw PatientRegistrationException.microchipConflict();
        }
        Patient updated;
        try {
            updated = patients.update(metadata.tenantId(), patientId, patient);
        } catch (DataIntegrityViolationException exception) {
            throw PatientRegistrationException.microchipConflict();
        }
        List<String> changed = changedFields(current, updated);
        writer.append(new AuditRecord(
                UUID.randomUUID(),
                metadata.tenantId(),
                metadata.actorUserId(),
                metadata.branchId(),
                "PATIENT",
                updated.id(),
                "PATIENT_UPDATED",
                clock.instant(),
                null,
                metadata.correlationId(),
                null,
                Map.of(
                        "changedFields", changed,
                        "ownerId", updated.ownerId().toString(),
                        "ownerReassigned", !Objects.equals(current.ownerId(), updated.ownerId()),
                        "name", updated.name(),
                        "species", updated.species(),
                        "sex", updated.sex(),
                        "sterilized", Boolean.TRUE.equals(updated.sterilized()))));
        return updated;
    }

    private static List<String> changedFields(Patient before, Patient after) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(before.ownerId(), after.ownerId())) changed.add("ownerId");
        if (!Objects.equals(before.name(), after.name())) changed.add("name");
        if (!Objects.equals(before.species(), after.species())) changed.add("species");
        if (!Objects.equals(before.breed(), after.breed())) changed.add("breed");
        if (!Objects.equals(before.sex(), after.sex())) changed.add("sex");
        if (!Objects.equals(before.birthDate(), after.birthDate())) changed.add("birthDate");
        if (!Objects.equals(before.color(), after.color())) changed.add("color");
        if (!Objects.equals(before.microchip(), after.microchip())) changed.add("microchip");
        if (!Objects.equals(before.sterilized(), after.sterilized())) changed.add("sterilized");
        return List.copyOf(changed);
    }

    private static NewPatient normalize(NewPatient value) {
        if (value == null || value.ownerId() == null) throw new IllegalArgumentException("Owner is required");
        LocalDate birthDate = value.birthDate();
        if (birthDate != null && birthDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Birth date cannot be future");
        }
        return new NewPatient(
                value.ownerId(),
                required(value.name(), 100),
                required(value.species(), 50),
                optional(value.breed(), 100, false),
                requiredUpper(value.sex(), 20),
                birthDate,
                optional(value.color(), 80, false),
                optional(value.microchip(), 80, true),
                value.sterilized());
    }

    private static String required(String value, int max) {
        String normalized = optional(value, max, false);
        if (normalized == null) throw new IllegalArgumentException("Patient value required");
        return normalized;
    }

    private static String requiredUpper(String value, int max) {
        String normalized = optional(value, max, true);
        if (normalized == null) throw new IllegalArgumentException("Patient value required");
        return normalized;
    }

    private static String optional(String value, int max, boolean upper) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (upper) normalized = normalized.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
        if (normalized.length() > max) throw new IllegalArgumentException("Patient value too long");
        return normalized;
    }
}
