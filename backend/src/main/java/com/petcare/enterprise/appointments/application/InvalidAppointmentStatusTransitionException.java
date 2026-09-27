package com.petcare.enterprise.appointments.application;

public final class InvalidAppointmentStatusTransitionException extends RuntimeException {

    public InvalidAppointmentStatusTransitionException() {
        super("La transición de estado solicitada no está permitida para la cita.");
    }

    public InvalidAppointmentStatusTransitionException(String currentStatus, String targetStatus) {
        super("La transición de estado de '" + currentStatus + "' hacia '" + targetStatus + "' no está permitida.");
    }
}
