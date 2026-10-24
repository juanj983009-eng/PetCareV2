package com.petcare.enterprise.clinical.application;

public final class ConsultationAccessDeniedException extends RuntimeException {

    public ConsultationAccessDeniedException(String message) {
        super(message);
    }
}
