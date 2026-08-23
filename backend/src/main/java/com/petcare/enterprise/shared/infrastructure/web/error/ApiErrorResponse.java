package com.petcare.enterprise.shared.infrastructure.web.error;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ApiErrorResponse(
        String code,
        String message,
        List<ApiFieldError> fieldErrors,
        UUID correlationId) {

    public ApiErrorResponse {
        Objects.requireNonNull(code, "Error code is required");
        Objects.requireNonNull(message, "Error message is required");
        fieldErrors = List.copyOf(Objects.requireNonNull(fieldErrors, "Field errors are required"));
        Objects.requireNonNull(correlationId, "Correlation id is required");
    }
}
