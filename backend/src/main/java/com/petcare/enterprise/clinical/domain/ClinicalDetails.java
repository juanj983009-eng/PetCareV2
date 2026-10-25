package com.petcare.enterprise.clinical.domain;

import com.petcare.enterprise.clinical.application.InvalidConsultationDataException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ClinicalDetails(
        VitalSigns vitalSigns,
        String observations,
        Map<String, String> variableDetails) {

    public static final int MAX_OBSERVATIONS_LENGTH = 2000;
    public static final int MAX_VARIABLE_DETAILS_ENTRIES = 20;
    public static final int MAX_KEY_LENGTH = 50;
    public static final int MAX_VALUE_LENGTH = 500;

    public ClinicalDetails {
        if (observations != null) {
            String trimmed = observations.trim();
            if (trimmed.length() > MAX_OBSERVATIONS_LENGTH) {
                throw new InvalidConsultationDataException(
                        "observations",
                        String.format("Las observaciones clínicas no deben exceder los %d caracteres.", MAX_OBSERVATIONS_LENGTH));
            }
            observations = trimmed.isEmpty() ? null : trimmed;
        }

        if (variableDetails != null && !variableDetails.isEmpty()) {
            if (variableDetails.size() > MAX_VARIABLE_DETAILS_ENTRIES) {
                throw new InvalidConsultationDataException(
                        "variableDetails",
                        String.format("No se permiten más de %d detalles clínicos variables.", MAX_VARIABLE_DETAILS_ENTRIES));
            }
            Map<String, String> validated = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : variableDetails.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || key.isBlank()) {
                    throw new InvalidConsultationDataException(
                            "variableDetails",
                            "Las claves de los detalles clínicos variables no pueden estar vacías.");
                }
                String trimmedKey = key.trim();
                if (trimmedKey.length() > MAX_KEY_LENGTH) {
                    throw new InvalidConsultationDataException(
                            "variableDetails",
                            String.format("La clave '%s' excede el límite de %d caracteres.", trimmedKey, MAX_KEY_LENGTH));
                }
                String trimmedVal = value != null ? value.trim() : "";
                if (trimmedVal.length() > MAX_VALUE_LENGTH) {
                    throw new InvalidConsultationDataException(
                            "variableDetails",
                            String.format("El valor para '%s' excede el límite de %d caracteres.", trimmedKey, MAX_VALUE_LENGTH));
                }
                validated.put(trimmedKey, trimmedVal);
            }
            variableDetails = Collections.unmodifiableMap(validated);
        } else {
            variableDetails = Collections.emptyMap();
        }
    }

    public static ClinicalDetails empty() {
        return new ClinicalDetails(null, null, Collections.emptyMap());
    }
}
