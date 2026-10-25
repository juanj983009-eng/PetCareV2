package com.petcare.enterprise.appointments.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentConsultationGateway {

    Optional<AppointmentConsultationContext> findForConsultation(UUID tenantId, UUID appointmentId);

    boolean startAttention(StartAppointmentAttentionCommand command);

    boolean finalizeAttention(FinalizeAppointmentAttentionCommand command);

    record AppointmentConsultationContext(
            UUID id,
            UUID tenantId,
            UUID branchId,
            String branchName,
            boolean branchActive,
            UUID patientId,
            UUID veterinarianId,
            String veterinarianFullName,
            boolean veterinarianActiveAndAssigned,
            String status,
            Instant startsAt,
            Instant endsAt) {
    }

    record StartAppointmentAttentionCommand(
            UUID tenantId,
            UUID appointmentId,
            UUID branchId,
            UUID actorUserId,
            Instant occurredAt,
            UUID correlationId) {
    }

    record FinalizeAppointmentAttentionCommand(
            UUID tenantId,
            UUID appointmentId,
            UUID branchId,
            UUID actorUserId,
            Instant occurredAt,
            UUID correlationId) {
    }
}
