package com.petcare.enterprise.appointments.application;

public final class AppointmentTimeConflictException extends RuntimeException {

    public AppointmentTimeConflictException() {
        super("El veterinario seleccionado ya tiene una cita activa programada en el intervalo indicado.");
    }
}
