package com.petcare.enterprise.shared.infrastructure.web.error;

import java.util.Objects;

public record ApiFieldError(String field, String code, String message) {

    public ApiFieldError {
        Objects.requireNonNull(field, "Field is required");
        Objects.requireNonNull(code, "Field error code is required");
        Objects.requireNonNull(message, "Field error message is required");
    }

    public static ApiFieldError of(String field, ApiFieldErrorCode code, String message) {
        return new ApiFieldError(field, code.name(), message);
    }
}
