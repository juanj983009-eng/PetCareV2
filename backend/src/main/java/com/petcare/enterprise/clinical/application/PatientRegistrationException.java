package com.petcare.enterprise.clinical.application;

public final class PatientRegistrationException extends RuntimeException {
    public enum Kind { OWNER_UNAVAILABLE, MICROCHIP_CONFLICT }

    private final Kind kind;

    private PatientRegistrationException(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static PatientRegistrationException ownerUnavailable() {
        return new PatientRegistrationException(Kind.OWNER_UNAVAILABLE);
    }

    public static PatientRegistrationException microchipConflict() {
        return new PatientRegistrationException(Kind.MICROCHIP_CONFLICT);
    }
}
