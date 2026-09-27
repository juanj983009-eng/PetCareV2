package com.petcare.enterprise.appointments.presentation;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentSummary;
import java.time.Instant;
import java.util.UUID;

record AppointmentCreatedResponse(
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

    static AppointmentCreatedResponse from(AppointmentSummary summary) {
        return new AppointmentCreatedResponse(
                summary.id(),
                summary.startsAt(),
                summary.endsAt(),
                summary.appointmentType(),
                summary.status(),
                summary.patientId(),
                summary.patientName(),
                summary.branchId(),
                summary.branchName(),
                summary.veterinarianId(),
                summary.veterinarianFullName());
    }
}
