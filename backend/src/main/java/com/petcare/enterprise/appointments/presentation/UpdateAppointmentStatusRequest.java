package com.petcare.enterprise.appointments.presentation;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.UpdateAppointmentStatusCommand;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

record UpdateAppointmentStatusRequest(
        @NotBlank(message = "El estado de destino es obligatorio.") String status) {

    UpdateAppointmentStatusCommand toCommand(UUID appointmentId) {
        return new UpdateAppointmentStatusCommand(appointmentId, status);
    }
}
