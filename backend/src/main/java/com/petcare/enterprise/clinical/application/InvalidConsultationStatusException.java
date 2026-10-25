package com.petcare.enterprise.clinical.application;

public class InvalidConsultationStatusException extends RuntimeException {

    private final String currentStatus;
    private final String expectedStatus;

    public InvalidConsultationStatusException(String currentStatus, String expectedStatus) {
        super(String.format("La consulta se encuentra en estado %s, se esperaba %s para realizar esta operación.",
                currentStatus, expectedStatus));
        this.currentStatus = currentStatus;
        this.expectedStatus = expectedStatus;
    }

    public InvalidConsultationStatusException(String message) {
        super(message);
        this.currentStatus = null;
        this.expectedStatus = null;
    }

    public String currentStatus() {
        return currentStatus;
    }

    public String expectedStatus() {
        return expectedStatus;
    }
}
