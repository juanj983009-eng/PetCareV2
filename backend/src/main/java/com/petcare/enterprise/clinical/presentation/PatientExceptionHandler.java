package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.PatientRegistrationException;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorCode;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponse;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponseFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class PatientExceptionHandler {
    private final ApiErrorResponseFactory errors;
    PatientExceptionHandler(ApiErrorResponseFactory errors) { this.errors=errors; }
    @ExceptionHandler(PatientRegistrationException.class) ResponseEntity<ApiErrorResponse> ownerUnavailable(PatientRegistrationException ignored) { return errors.response(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "No se encontró el propietario solicitado."); }
}
