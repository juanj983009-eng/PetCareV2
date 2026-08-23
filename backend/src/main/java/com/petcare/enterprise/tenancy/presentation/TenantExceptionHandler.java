package com.petcare.enterprise.tenancy.presentation;

import com.petcare.enterprise.audit.application.CorrelationIdProvider;
import com.petcare.enterprise.tenancy.application.TenantPersistenceConflictException;
import com.petcare.enterprise.tenancy.application.TenantSlugAlreadyExistsException;
import com.petcare.enterprise.tenancy.domain.DomainValidationException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TenantController.class)
class TenantExceptionHandler {

    private final CorrelationIdProvider correlationIdProvider;

    TenantExceptionHandler(CorrelationIdProvider correlationIdProvider) {
        this.correlationIdProvider = correlationIdProvider;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException exception) {
        List<ApiErrorResponse.FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", errors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, DomainValidationException.class})
    ResponseEntity<ApiErrorResponse> malformedRequest(Exception exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "The request is invalid",
                List.of());
    }

    @ExceptionHandler(TenantSlugAlreadyExistsException.class)
    ResponseEntity<ApiErrorResponse> duplicatedSlug(
            TenantSlugAlreadyExistsException exception) {
        return response(
                HttpStatus.CONFLICT,
                "TENANT_SLUG_ALREADY_EXISTS",
                "The tenant slug is already in use",
                List.of());
    }

    @ExceptionHandler(TenantPersistenceConflictException.class)
    ResponseEntity<ApiErrorResponse> persistenceConflict(
            TenantPersistenceConflictException exception) {
        return response(
                HttpStatus.CONFLICT,
                "TENANT_DATA_CONFLICT",
                "Tenant data conflicts with an existing record",
                List.of());
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            List<ApiErrorResponse.FieldError> fieldErrors) {
        UUID correlationId = correlationIdProvider.currentCorrelationId();
        return ResponseEntity.status(status)
                .header(TenantController.CORRELATION_HEADER, correlationId.toString())
                .body(new ApiErrorResponse(code, message, fieldErrors, correlationId));
    }
}
