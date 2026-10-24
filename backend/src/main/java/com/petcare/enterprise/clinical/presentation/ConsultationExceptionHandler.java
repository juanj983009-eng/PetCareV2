package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationAccessDeniedException;
import com.petcare.enterprise.clinical.application.ConsultationResourceNotFoundException;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorCode;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponse;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponseFactory;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ConsultationExceptionHandler {

    private final ApiErrorResponseFactory errors;

    ConsultationExceptionHandler(ApiErrorResponseFactory errors) {
        this.errors = Objects.requireNonNull(errors, "errors is required");
    }

    @ExceptionHandler(ConsultationAccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> accessDenied(ConsultationAccessDeniedException exception) {
        return errors.response(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.ACCESS_DENIED,
                exception.getMessage());
    }

    @ExceptionHandler(ConsultationResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> resourceNotFound(ConsultationResourceNotFoundException exception) {
        return errors.response(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RESOURCE_NOT_FOUND,
                exception.getMessage());
    }
}
