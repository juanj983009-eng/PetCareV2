package com.petcare.enterprise.clinical.domain;

public enum DiagnosisType {
    PRESUNTIVO,
    DEFINITIVO;

    public static DiagnosisType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DiagnosisType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
