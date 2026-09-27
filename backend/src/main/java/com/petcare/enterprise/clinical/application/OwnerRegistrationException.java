package com.petcare.enterprise.clinical.application;

public class OwnerRegistrationException extends RuntimeException {
    public enum Kind { DOCUMENT_CONFLICT, PHONE_CONFIRMATION_REQUIRED, OWNER_NOT_FOUND }

    private final Kind kind;

    private OwnerRegistrationException(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static OwnerRegistrationException documentConflict() {
        return new OwnerRegistrationException(Kind.DOCUMENT_CONFLICT);
    }

    public static OwnerRegistrationException phoneConfirmationRequired() {
        return new OwnerRegistrationException(Kind.PHONE_CONFIRMATION_REQUIRED);
    }

    public static OwnerRegistrationException ownerNotFound() {
        return new OwnerRegistrationException(Kind.OWNER_NOT_FOUND);
    }
}
