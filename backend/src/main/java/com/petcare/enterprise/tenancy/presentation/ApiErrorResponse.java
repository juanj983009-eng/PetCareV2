package com.petcare.enterprise.tenancy.presentation;

import java.util.List;
import java.util.UUID;

public record ApiErrorResponse(
        String code,
        String message,
        List<FieldError> fieldErrors,
        UUID correlationId) {

    public record FieldError(String field, String message) {
    }
}
