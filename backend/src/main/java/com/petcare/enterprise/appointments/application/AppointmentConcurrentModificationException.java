package com.petcare.enterprise.appointments.application;

public final class AppointmentConcurrentModificationException extends RuntimeException {

    public AppointmentConcurrentModificationException() {
        super("La cita fue modificada concurrentemente por otra operación. Actualice la agenda e intente nuevamente.");
    }
}
