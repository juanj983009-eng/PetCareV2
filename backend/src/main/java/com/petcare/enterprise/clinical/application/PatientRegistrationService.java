package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditMetadata;
import com.petcare.enterprise.audit.application.AuditMetadataProvider;
import com.petcare.enterprise.audit.application.AuditRecord;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientRegistrationService implements PatientRegistrationUseCase {
    private final PatientRegistrationRepository patients; private final AuditMetadataProvider audit; private final AuditLogWriter writer; private final Clock clock;
    public PatientRegistrationService(PatientRegistrationRepository patients, AuditMetadataProvider audit, AuditLogWriter writer, Clock clock) { this.patients = patients; this.audit = audit; this.writer = writer; this.clock = clock; }
    @Override @Transactional
    public Patient register(NewPatient input) {
        AuditMetadata metadata = audit.requireTenantMetadata();
        NewPatient patient = normalize(input);
        if (!patients.activeOwnerExists(metadata.tenantId(), patient.ownerId())) throw PatientRegistrationException.ownerUnavailable();
        Patient created = patients.createPatientAndMedicalRecord(metadata.tenantId(), patient);
        writer.append(new AuditRecord(UUID.randomUUID(), metadata.tenantId(), metadata.actorUserId(), metadata.branchId(), "PATIENT", created.id(), "PATIENT_CREATED", clock.instant(), null, metadata.correlationId(), null, Map.of("ownerId", created.ownerId().toString(), "name", created.name(), "species", created.species(), "sex", created.sex())));
        return created;
    }
    private static NewPatient normalize(NewPatient value) {
        if (value == null || value.ownerId() == null) throw new IllegalArgumentException("Owner is required");
        LocalDate birthDate = value.birthDate(); if (birthDate != null && birthDate.isAfter(LocalDate.now())) throw new IllegalArgumentException("Birth date cannot be future");
        return new NewPatient(value.ownerId(), required(value.name(),100), required(value.species(),50), optional(value.breed(),100,false), requiredUpper(value.sex(),20), birthDate, optional(value.color(),80,false), optional(value.microchip(),80,true), value.sterilized());
    }
    private static String required(String value, int max) { String normalized=optional(value,max,false); if(normalized==null) throw new IllegalArgumentException("Patient value required"); return normalized; }
    private static String requiredUpper(String value, int max) { String normalized=optional(value,max,true); if(normalized==null) throw new IllegalArgumentException("Patient value required"); return normalized; }
    private static String optional(String value, int max, boolean upper) { if(value==null || value.isBlank()) return null; String normalized=value.trim(); if(upper) normalized=normalized.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT); if(normalized.length()>max) throw new IllegalArgumentException("Patient value too long"); return normalized; }
}
