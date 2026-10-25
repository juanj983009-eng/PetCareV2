package com.petcare.enterprise.clinical.application;

public class PrescriptionIdempotencyConflictException extends RuntimeException {

    public PrescriptionIdempotencyConflictException(String message) {
        super(message);
    }
}
