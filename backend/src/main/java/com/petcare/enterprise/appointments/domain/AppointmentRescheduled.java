package com.petcare.enterprise.appointments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal non-durable domain event emitted within the MVP modular monolith
 * when a tenant appointment is rescheduled. Processed exclusively AFTER_COMMIT.
 */
public record AppointmentRescheduled(
        UUID tenantId,
        UUID appointmentId,
        UUID previousBranchId,
        UUID newBranchId,
        UUID previousVeterinarianId,
        UUID newVeterinarianId,
        Instant previousStartsAt,
        Instant previousEndsAt,
        Instant newStartsAt,
        Instant newEndsAt,
        String previousStatus,
        String newStatus,
        UUID rescheduledBy,
        UUID correlationId) {

    public AppointmentRescheduled {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(appointmentId, "appointmentId is required");
        Objects.requireNonNull(previousBranchId, "previousBranchId is required");
        Objects.requireNonNull(newBranchId, "newBranchId is required");
        Objects.requireNonNull(previousVeterinarianId, "previousVeterinarianId is required");
        Objects.requireNonNull(newVeterinarianId, "newVeterinarianId is required");
        Objects.requireNonNull(previousStartsAt, "previousStartsAt is required");
        Objects.requireNonNull(previousEndsAt, "previousEndsAt is required");
        Objects.requireNonNull(newStartsAt, "newStartsAt is required");
        Objects.requireNonNull(newEndsAt, "newEndsAt is required");
        Objects.requireNonNull(previousStatus, "previousStatus is required");
        Objects.requireNonNull(newStatus, "newStatus is required");
        Objects.requireNonNull(rescheduledBy, "rescheduledBy is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
    }
}
