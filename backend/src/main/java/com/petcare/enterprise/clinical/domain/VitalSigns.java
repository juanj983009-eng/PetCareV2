package com.petcare.enterprise.clinical.domain;

import com.petcare.enterprise.clinical.application.InvalidConsultationDataException;
import java.util.Objects;

public record VitalSigns(
        Double temperatureCelsius,
        Double weightKg,
        Integer heartRateBpm,
        Integer respiratoryRateBpm,
        Double capillaryRefillTimeSeconds,
        String mucousMembranes) {

    public static final double MIN_TEMPERATURE_C = 30.0;
    public static final double MAX_TEMPERATURE_C = 45.0;
    public static final double MIN_WEIGHT_KG = 0.01;
    public static final double MAX_WEIGHT_KG = 500.0;
    public static final int MIN_HEART_RATE_BPM = 20;
    public static final int MAX_HEART_RATE_BPM = 400;
    public static final int MIN_RESPIRATORY_RATE_BPM = 5;
    public static final int MAX_RESPIRATORY_RATE_BPM = 200;
    public static final double MIN_CRT_SECONDS = 0.1;
    public static final double MAX_CRT_SECONDS = 10.0;
    public static final int MAX_MUCOUS_MEMBRANES_LENGTH = 50;

    public VitalSigns {
        if (temperatureCelsius != null) {
            validateFinite(temperatureCelsius, "temperatura corporal");
            if (temperatureCelsius < MIN_TEMPERATURE_C || temperatureCelsius > MAX_TEMPERATURE_C) {
                throw new InvalidConsultationDataException(
                        "temperatureCelsius",
                        String.format("La temperatura debe estar entre %.1f y %.1f °C.", MIN_TEMPERATURE_C, MAX_TEMPERATURE_C));
            }
        }
        if (weightKg != null) {
            validateFinite(weightKg, "peso corporal");
            if (weightKg < MIN_WEIGHT_KG || weightKg > MAX_WEIGHT_KG) {
                throw new InvalidConsultationDataException(
                        "weightKg",
                        String.format("El peso debe estar entre %.2f y %.1f kg.", MIN_WEIGHT_KG, MAX_WEIGHT_KG));
            }
        }
        if (heartRateBpm != null) {
            if (heartRateBpm < MIN_HEART_RATE_BPM || heartRateBpm > MAX_HEART_RATE_BPM) {
                throw new InvalidConsultationDataException(
                        "heartRateBpm",
                        String.format("La frecuencia cardíaca debe estar entre %d y %d lpm.", MIN_HEART_RATE_BPM, MAX_HEART_RATE_BPM));
            }
        }
        if (respiratoryRateBpm != null) {
            if (respiratoryRateBpm < MIN_RESPIRATORY_RATE_BPM || respiratoryRateBpm > MAX_RESPIRATORY_RATE_BPM) {
                throw new InvalidConsultationDataException(
                        "respiratoryRateBpm",
                        String.format("La frecuencia respiratoria debe estar entre %d y %d rpm.", MIN_RESPIRATORY_RATE_BPM, MAX_RESPIRATORY_RATE_BPM));
            }
        }
        if (capillaryRefillTimeSeconds != null) {
            validateFinite(capillaryRefillTimeSeconds, "tiempo de llenado capilar");
            if (capillaryRefillTimeSeconds < MIN_CRT_SECONDS || capillaryRefillTimeSeconds > MAX_CRT_SECONDS) {
                throw new InvalidConsultationDataException(
                        "capillaryRefillTimeSeconds",
                        String.format("El tiempo de llenado capilar debe estar entre %.1f y %.1f segundos.", MIN_CRT_SECONDS, MAX_CRT_SECONDS));
            }
        }
        if (mucousMembranes != null) {
            String trimmed = mucousMembranes.trim();
            if (trimmed.length() > MAX_MUCOUS_MEMBRANES_LENGTH) {
                throw new InvalidConsultationDataException(
                        "mucousMembranes",
                        String.format("El estado de mucosas no debe exceder los %d caracteres.", MAX_MUCOUS_MEMBRANES_LENGTH));
            }
            mucousMembranes = trimmed.isEmpty() ? null : trimmed;
        }
    }

    private static void validateFinite(Double value, String fieldLabel) {
        if (value.isNaN() || value.isInfinite() || value <= 0) {
            throw new InvalidConsultationDataException(
                    fieldLabel,
                    "El valor de " + fieldLabel + " debe ser un número positivo válido.");
        }
    }
}
