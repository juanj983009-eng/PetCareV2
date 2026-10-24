package com.petcare.enterprise.clinical.application;

public final class ConsultationResourceNotFoundException extends RuntimeException {

    public ConsultationResourceNotFoundException() {
        super("No se encontró la cita, el paciente activo o la historia clínica solicitada.");
    }
}
