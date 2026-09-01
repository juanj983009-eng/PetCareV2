package com.petcare.enterprise.clinical.application;

public final class PatientRegistrationException extends RuntimeException {
    private PatientRegistrationException() { super("Owner is unavailable"); }
    public static PatientRegistrationException ownerUnavailable() { return new PatientRegistrationException(); }
}
