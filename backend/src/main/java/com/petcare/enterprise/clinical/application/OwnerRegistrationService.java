package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditMetadata;
import com.petcare.enterprise.audit.application.AuditMetadataProvider;
import com.petcare.enterprise.audit.application.AuditRecord;
import com.petcare.enterprise.clinical.application.OwnerRegistrationUseCase.NewOwner;
import com.petcare.enterprise.clinical.application.OwnerRegistrationUseCase.Owner;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OwnerRegistrationService implements OwnerRegistrationUseCase {
    private final OwnerSearchRepository owners; private final AuditMetadataProvider audit; private final AuditLogWriter writer; private final Clock clock;
    public OwnerRegistrationService(OwnerSearchRepository owners, AuditMetadataProvider audit, AuditLogWriter writer, Clock clock) { this.owners = owners; this.audit = audit; this.writer = writer; this.clock = clock; }
    @Override @Transactional
    public Owner register(NewOwner input, boolean confirmPhoneDuplicate) {
        AuditMetadata metadata = audit.requireTenantMetadata();
        NewOwner owner = normalize(input);
        if (owner.documentNumber() != null && owners.documentExists(metadata.tenantId(), owner.documentNumber())) throw OwnerRegistrationException.documentConflict();
        if (!confirmPhoneDuplicate && owners.phoneExists(metadata.tenantId(), owner.phone())) throw OwnerRegistrationException.phoneConfirmationRequired();
        Owner created;
        try { created = owners.create(metadata.tenantId(), owner); }
        catch (org.springframework.dao.DataIntegrityViolationException exception) { throw OwnerRegistrationException.documentConflict(); }
        writer.append(new AuditRecord(UUID.randomUUID(), metadata.tenantId(), metadata.actorUserId(), metadata.branchId(), "OWNER", created.id(), "OWNER_CREATED", clock.instant(), null, metadata.correlationId(), null, Map.of("firstName", created.firstName(), "lastName", created.lastName(), "documentNumber", created.documentNumber() == null ? "" : created.documentNumber(), "phone", created.phone(), "notificationsAccepted", created.notificationsAccepted())));
        return created;
    }
    private static NewOwner normalize(NewOwner input) {
        if (input == null) throw new IllegalArgumentException("Owner is required");
        return new NewOwner(required(input.firstName(), 100), required(input.lastName(), 100), optional(input.documentType(), 20, false), optional(input.documentNumber(), 30, true), normalizePhone(input.phone()), optionalEmail(input.email()), optional(input.address(), 250, false), input.notificationsAccepted());
    }
    private static String required(String value, int max) { String normalized = optional(value, max, false); if (normalized == null) throw new IllegalArgumentException("Required owner value"); return normalized; }
    private static String optional(String value, int max, boolean upper) { if (value == null || value.isBlank()) return null; String normalized = value.trim(); if (upper) normalized = normalized.replaceAll("[\\s-]+", "").toUpperCase(java.util.Locale.ROOT); if (normalized.length() > max) throw new IllegalArgumentException("Owner value too long"); return normalized; }
    private static String normalizePhone(String value) { if (value == null) throw new IllegalArgumentException("Phone required"); String normalized = value.replaceAll("[^0-9+]", ""); if (normalized.isBlank() || normalized.length() > 30) throw new IllegalArgumentException("Invalid phone"); return normalized; }
    private static String optionalEmail(String value) { String email = optional(value, 180, false); if (email == null) return null; email = email.toLowerCase(java.util.Locale.ROOT); if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Invalid email"); return email; }
}
