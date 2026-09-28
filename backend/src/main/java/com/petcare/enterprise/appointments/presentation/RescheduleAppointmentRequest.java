package com.petcare.enterprise.appointments.presentation;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.RescheduleAppointmentCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

record RescheduleAppointmentRequest(
        @NotNull(message = "La sucursal es obligatoria.") UUID branchId,
        @NotNull(message = "El veterinario es obligatorio.") UUID veterinarianId,
        @NotBlank(message = "La fecha y hora de inicio son obligatorias.") String startsAtLocal,
        @NotBlank(message = "La fecha y hora de término son obligatorias.") String endsAtLocal) {

    RescheduleAppointmentCommand toCommand(UUID appointmentId) {
        return new RescheduleAppointmentCommand(
                appointmentId,
                branchId,
                veterinarianId,
                startsAtLocal,
                endsAtLocal);
    }
}
