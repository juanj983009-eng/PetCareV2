package com.petcare.enterprise.appointments.application;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentPage;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentSummary;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.BranchOption;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.NormalizedAppointmentQuery;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.VeterinarianOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentScheduleRepository {

    AppointmentPage search(UUID tenantId, NormalizedAppointmentQuery query);

    List<BranchOption> findActiveBranches(UUID tenantId);

    List<VeterinarianOption> findActiveVeterinarians(UUID tenantId);

    Optional<BranchOption> findActiveBranchById(UUID tenantId, UUID branchId);

    Optional<String> findActivePatientName(UUID tenantId, UUID patientId);

    Optional<VeterinarianEligibility> findActiveUserEligibility(UUID tenantId, UUID userId, UUID branchId);

    boolean hasOverlappingActiveAppointment(UUID tenantId, UUID veterinarianId, Instant startsAt, Instant endsAt);

    boolean hasOverlappingActiveAppointmentExcluding(
            UUID tenantId,
            UUID veterinarianId,
            Instant startsAt,
            Instant endsAt,
            UUID excludedAppointmentId);

    AppointmentSummary insertAppointment(NewAppointmentRecord record);

    Optional<AppointmentSummary> findById(UUID tenantId, UUID appointmentId);

    boolean updateStatusConditionally(
            UUID tenantId,
            UUID appointmentId,
            String expectedPreviousStatus,
            String newStatus,
            Instant updatedAt);

    boolean rescheduleConditionally(RescheduleAppointmentMutation mutation);

    boolean cancelConditionally(CancelAppointmentMutation mutation);

    void insertStatusHistory(AppointmentStatusHistoryRecord record);

    record VeterinarianEligibility(
            UUID id,
            String fullName,
            boolean hasVeterinarianRole,
            boolean assignedToBranch) {
    }

    record NewAppointmentRecord(
            UUID id,
            UUID tenantId,
            UUID branchId,
            String branchName,
            UUID patientId,
            String patientName,
            UUID veterinarianId,
            String veterinarianFullName,
            Instant startsAt,
            Instant endsAt,
            String appointmentType,
            String status,
            String reason,
            String notes,
            UUID createdBy,
            Instant createdAt) {
    }

    record RescheduleAppointmentMutation(
            UUID tenantId,
            UUID appointmentId,
            String expectedPreviousStatus,
            UUID expectedBranchId,
            UUID expectedVeterinarianId,
            Instant expectedStartsAt,
            Instant expectedEndsAt,
            UUID newBranchId,
            UUID newVeterinarianId,
            Instant newStartsAt,
            Instant newEndsAt,
            String newStatus,
            Instant updatedAt) {
    }

    record CancelAppointmentMutation(
            UUID tenantId,
            UUID appointmentId,
            String expectedPreviousStatus,
            UUID expectedBranchId,
            UUID expectedVeterinarianId,
            Instant expectedStartsAt,
            Instant expectedEndsAt,
            String cancellationReason,
            Instant cancelledAt,
            UUID cancelledBy) {
    }

    record AppointmentStatusHistoryRecord(
            UUID id,
            UUID tenantId,
            UUID appointmentId,
            String previousStatus,
            String newStatus,
            UUID changedBy,
            Instant changedAt,
            UUID correlationId) {
    }
}
