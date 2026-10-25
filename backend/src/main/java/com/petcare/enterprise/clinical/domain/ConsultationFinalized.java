package com.petcare.enterprise.clinical.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal non-durable domain event emitted within the MVP modular monolith
 * when a consultation is finalized. Processed exclusively AFTER_COMMIT.
 */
public record ConsultationFinalized(
        UUID tenantId,
        UUID consultationId,
        UUID appointmentId,
        UUID patientId,
        UUID branchId,
        UUID veterinarianId,
        Instant finalizedAt,
        UUID correlationId) {

    public ConsultationFinalized {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(consultationId, "consultationId is required");
        Objects.requireNonNull(appointmentId, "appointmentId is required");
        Objects.requireNonNull(patientId, "patientId is required");
        Objects.requireNonNull(branchId, "branchId is required");
        Objects.requireNonNull(veterinarianId, "veterinarianId is required");
        Objects.requireNonNull(finalizedAt, "finalizedAt is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
    }
}
