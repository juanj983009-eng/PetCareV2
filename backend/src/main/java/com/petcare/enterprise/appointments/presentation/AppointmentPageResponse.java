package com.petcare.enterprise.appointments.presentation;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentPage;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record AppointmentPageResponse(
        List<AppointmentItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        long scheduledCount,
        long confirmedCount,
        long waitingCount,
        long noShowCount) {

    static AppointmentPageResponse from(AppointmentPage page) {
        return new AppointmentPageResponse(
                page.content().stream().map(AppointmentItemResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.scheduledCount(),
                page.confirmedCount(),
                page.waitingCount(),
                page.noShowCount());
    }

    record AppointmentItemResponse(
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

        static AppointmentItemResponse from(AppointmentSummary summary) {
            return new AppointmentItemResponse(
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
}
