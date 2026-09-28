package com.petcare.enterprise.appointments.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AppointmentScheduleUseCase {

    AppointmentPage listSchedule(AppointmentScheduleQuery query);

    AppointmentFilterOptions listOptions();

    default AppointmentFilterOptions listOptions(String branchId) {
        return listOptions();
    }

    AppointmentSummary createAppointment(CreateAppointmentCommand command);

    AppointmentSummary updateStatus(UpdateAppointmentStatusCommand command);

    AppointmentSummary rescheduleAppointment(RescheduleAppointmentCommand command);

    AppointmentSummary cancelAppointment(CancelAppointmentCommand command);

    record UpdateAppointmentStatusCommand(
            UUID appointmentId,
            String status) {
    }

    record RescheduleAppointmentCommand(
            UUID appointmentId,
            UUID branchId,
            UUID veterinarianId,
            String startsAtLocal,
            String endsAtLocal) {
    }

    record CancelAppointmentCommand(
            UUID appointmentId,
            String reason) {
    }

    record CreateAppointmentCommand(
            UUID patientId,
            UUID branchId,
            UUID veterinarianId,
            String startsAtLocal,
            String endsAtLocal,
            String appointmentType,
            String reason,
            String notes) {
    }

    record AppointmentScheduleQuery(
            String date,
            String branchId,
            String veterinarianId,
            int page,
            int size,
            String direction) {
    }

    record NormalizedAppointmentQuery(
            LocalDate date,
            UUID branchId,
            UUID veterinarianId,
            int page,
            int size,
            boolean ascending) {
    }

    record AppointmentPage(
            List<AppointmentSummary> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    record AppointmentSummary(
            UUID id,
            Instant startsAt,
            Instant endsAt,
            String appointmentType,
            String status,
            UUID patientId,
            String patientName,
            UUID branchId,
            String branchName,
            UUID veterinarianId,
            String veterinarianFullName) {
    }

    record AppointmentFilterOptions(
            List<BranchOption> branches,
            List<VeterinarianOption> veterinarians) {
    }

    record BranchOption(
            UUID id,
            String name,
            String timeZone) {
    }

    record VeterinarianOption(
            UUID id,
            String fullName,
            List<UUID> branchIds) {

        public VeterinarianOption(UUID id, String fullName) {
            this(id, fullName, List.of());
        }
    }
}
