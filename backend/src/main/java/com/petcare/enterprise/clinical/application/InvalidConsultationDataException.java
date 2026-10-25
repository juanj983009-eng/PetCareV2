package com.petcare.enterprise.clinical.application;

public class InvalidConsultationDataException extends RuntimeException {

    private final String field;

    public InvalidConsultationDataException(String field, String message) {
        super(message);
        this.field = field;
    }

    public InvalidConsultationDataException(String message) {
        this(null, message);
    }

    public String field() {
        return field;
    }
}
