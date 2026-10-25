package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.ConsultationAccessDeniedException;
import com.petcare.enterprise.clinical.application.ConsultationResourceNotFoundException;
import com.petcare.enterprise.clinical.application.InvalidConsultationDataException;
import com.petcare.enterprise.clinical.application.InvalidConsultationStatusException;
import com.petcare.enterprise.clinical.application.PrescriptionIdempotencyConflictException;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorCode;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponse;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponseFactory;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiFieldError;
import java.util.List;
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

    @ExceptionHandler(InvalidConsultationDataException.class)
    ResponseEntity<ApiErrorResponse> invalidData(InvalidConsultationDataException exception) {
        if (exception.field() != null && !exception.field().isBlank()) {
            return errors.response(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.VALIDATION_FAILED,
                    exception.getMessage(),
                    List.of(new ApiFieldError(
                            exception.field(),
                            "INVALID_VALUE",
                            exception.getMessage())));
        }
        return errors.response(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_FAILED,
                exception.getMessage());
    }

    @ExceptionHandler(InvalidConsultationStatusException.class)
    ResponseEntity<ApiErrorResponse> invalidStatus(InvalidConsultationStatusException exception) {
        return errors.response(
                HttpStatus.CONFLICT,
                ApiErrorCode.INVALID_CONSULTATION_STATUS,
                exception.getMessage());
    }

    @ExceptionHandler(PrescriptionIdempotencyConflictException.class)
    ResponseEntity<ApiErrorResponse> idempotencyConflict(PrescriptionIdempotencyConflictException exception) {
        return errors.response(
                HttpStatus.CONFLICT,
                ApiErrorCode.CONFLICT,
                exception.getMessage());
    }
}
