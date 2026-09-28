package com.petcare.enterprise.appointments.presentation;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentScheduleQuery;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentSummary;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/appointments")
class AppointmentController {

    private final AppointmentScheduleUseCase schedule;

    AppointmentController(AppointmentScheduleUseCase schedule) {
        this.schedule = Objects.requireNonNull(schedule, "schedule is required");
    }

    @GetMapping
    AppointmentPageResponse listSchedule(
            @RequestParam(name = "date", required = false) String date,
            @RequestParam(name = "branchId", required = false) String branchId,
            @RequestParam(name = "veterinarianId", required = false) String veterinarianId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "direction", defaultValue = "asc") String direction) {
        return AppointmentPageResponse.from(schedule.listSchedule(new AppointmentScheduleQuery(
                date,
                branchId,
                veterinarianId,
                page,
                size,
                direction)));
    }

    @GetMapping("/options")
    AppointmentOptionsResponse listOptions(
            @RequestParam(name = "branchId", required = false) String branchId) {
        return AppointmentOptionsResponse.from(schedule.listOptions(branchId));
    }

    @PostMapping
    ResponseEntity<AppointmentCreatedResponse> createAppointment(
            @Valid @RequestBody CreateAppointmentRequest request) {
        AppointmentSummary created = schedule.createAppointment(request.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/appointments/" + created.id()))
                .body(AppointmentCreatedResponse.from(created));
    }

    @PatchMapping("/{appointmentId}/status")
    AppointmentCreatedResponse updateAppointmentStatus(
            @PathVariable("appointmentId") UUID appointmentId,
            @Valid @RequestBody UpdateAppointmentStatusRequest request) {
        AppointmentSummary updated = schedule.updateStatus(request.toCommand(appointmentId));
        return AppointmentCreatedResponse.from(updated);
    }

    @PutMapping("/{appointmentId}/schedule")
    AppointmentCreatedResponse rescheduleAppointment(
            @PathVariable("appointmentId") UUID appointmentId,
            @Valid @RequestBody RescheduleAppointmentRequest request) {
        AppointmentSummary updated = schedule.rescheduleAppointment(request.toCommand(appointmentId));
        return AppointmentCreatedResponse.from(updated);
    }

    @PutMapping("/{appointmentId}/cancellation")
    AppointmentCreatedResponse cancelAppointment(
            @PathVariable("appointmentId") UUID appointmentId,
            @Valid @RequestBody CancelAppointmentRequest request) {
        AppointmentSummary updated = schedule.cancelAppointment(request.toCommand(appointmentId));
        return AppointmentCreatedResponse.from(updated);
    }
}
