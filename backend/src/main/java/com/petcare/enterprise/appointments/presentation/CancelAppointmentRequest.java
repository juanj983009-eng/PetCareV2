package com.petcare.enterprise.appointments.presentation;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.CancelAppointmentCommand;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

record CancelAppointmentRequest(
        @NotBlank(message = "El motivo de cancelación es obligatorio.") String reason) {

    CancelAppointmentCommand toCommand(UUID appointmentId) {
        return new CancelAppointmentCommand(appointmentId, reason);
    }
}
