package com.petcare.enterprise.appointments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal non-durable domain event emitted within the MVP modular monolith
 * when a tenant appointment is cancelled. Processed exclusively AFTER_COMMIT.
 */
public record AppointmentCancelled(
        UUID tenantId,
        UUID appointmentId,
        UUID branchId,
        UUID patientId,
        UUID veterinarianId,
        String previousStatus,
        String newStatus,
        UUID cancelledBy,
        Instant cancelledAt,
        UUID correlationId) {

    public AppointmentCancelled {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(appointmentId, "appointmentId is required");
        Objects.requireNonNull(branchId, "branchId is required");
        Objects.requireNonNull(patientId, "patientId is required");
        Objects.requireNonNull(veterinarianId, "veterinarianId is required");
        Objects.requireNonNull(previousStatus, "previousStatus is required");
        Objects.requireNonNull(newStatus, "newStatus is required");
        Objects.requireNonNull(cancelledBy, "cancelledBy is required");
        Objects.requireNonNull(cancelledAt, "cancelledAt is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
    }
}
