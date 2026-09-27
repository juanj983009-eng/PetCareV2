package com.petcare.enterprise.appointments.application;

public final class AppointmentResourceNotFoundException extends RuntimeException {

    public AppointmentResourceNotFoundException() {
        super("El recurso solicitado para la cita no fue encontrado o no está disponible.");
    }
}
