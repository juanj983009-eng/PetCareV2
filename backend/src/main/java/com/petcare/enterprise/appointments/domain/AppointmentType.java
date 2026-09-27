package com.petcare.enterprise.appointments.domain;

import java.util.Locale;
import java.util.Optional;

public enum AppointmentType {
    CONSULTA_GENERAL,
    VACUNACION,
    CONTROL,
    URGENCIA,
    CIRUGIA,
    DESPARASITACION;

    public static Optional<AppointmentType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AppointmentType candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
