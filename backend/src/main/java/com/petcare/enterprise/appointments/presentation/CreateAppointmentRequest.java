package com.petcare.enterprise.appointments.presentation;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.CreateAppointmentCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

record CreateAppointmentRequest(
        @NotNull(message = "El paciente es obligatorio.") UUID patientId,
        @NotNull(message = "La sucursal es obligatoria.") UUID branchId,
        @NotNull(message = "El veterinario es obligatorio.") UUID veterinarianId,
        @NotBlank(message = "La fecha y hora de inicio es obligatoria.") String startsAtLocal,
        @NotBlank(message = "La fecha y hora de fin es obligatoria.") String endsAtLocal,
        @NotBlank(message = "El tipo de cita es obligatorio.") @Size(max = 80) String appointmentType,
        @Size(max = 500, message = "El motivo no puede superar los 500 caracteres.") String reason,
        @Size(max = 1000, message = "Las observaciones no pueden superar los 1000 caracteres.") String notes) {

    CreateAppointmentCommand toCommand() {
        return new CreateAppointmentCommand(
                patientId,
                branchId,
                veterinarianId,
                startsAtLocal,
                endsAtLocal,
                appointmentType,
                reason,
                notes);
    }
}
