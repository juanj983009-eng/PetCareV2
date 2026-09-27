package com.petcare.enterprise.appointments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal non-durable domain event emitted within the MVP modular monolith
 * when a tenant appointment is scheduled. Processed exclusively AFTER_COMMIT.
 */
public record AppointmentCreated(
        UUID tenantId,
        UUID appointmentId,
        UUID branchId,
        UUID patientId,
        UUID veterinarianId,
        UUID createdBy,
        Instant startsAt,
        Instant endsAt,
        String type,
        String status,
        UUID correlationId) {

    public AppointmentCreated {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(appointmentId, "appointmentId is required");
        Objects.requireNonNull(branchId, "branchId is required");
        Objects.requireNonNull(patientId, "patientId is required");
        Objects.requireNonNull(veterinarianId, "veterinarianId is required");
        Objects.requireNonNull(createdBy, "createdBy is required");
        Objects.requireNonNull(startsAt, "startsAt is required");
        Objects.requireNonNull(endsAt, "endsAt is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
    }
}
