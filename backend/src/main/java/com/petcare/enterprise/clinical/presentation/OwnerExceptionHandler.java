package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.OwnerRegistrationException;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorCode;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponse;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponseFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class OwnerExceptionHandler {
    private final ApiErrorResponseFactory errors;
    OwnerExceptionHandler(ApiErrorResponseFactory errors) { this.errors = errors; }
    @ExceptionHandler(OwnerRegistrationException.class)
    ResponseEntity<ApiErrorResponse> registration(OwnerRegistrationException exception) {
        return switch (exception.kind()) {
            case DOCUMENT_CONFLICT -> errors.response(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT, "Ya existe un propietario con ese documento en la clínica.");
            case PHONE_CONFIRMATION_REQUIRED -> errors.response(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT, "Existe un propietario con ese teléfono. Confirma para continuar.");
        };
    }
}
