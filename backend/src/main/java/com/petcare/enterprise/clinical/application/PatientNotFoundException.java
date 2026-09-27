package com.petcare.enterprise.clinical.application;

public final class PatientNotFoundException extends RuntimeException {
    public PatientNotFoundException() {
        super("Patient not found in current tenant");
    }
}
