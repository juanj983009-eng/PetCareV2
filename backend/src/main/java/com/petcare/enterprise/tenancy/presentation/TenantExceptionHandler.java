package com.petcare.enterprise.tenancy.presentation;

import com.petcare.enterprise.tenancy.application.TenantPersistenceConflictException;
import com.petcare.enterprise.tenancy.application.TenantSlugAlreadyExistsException;
import com.petcare.enterprise.tenancy.domain.DomainValidationException;
import jakarta.servlet.http.HttpServletRequest;
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", errors, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, DomainValidationException.class})
    ResponseEntity<ApiErrorResponse> malformedRequest(Exception exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "The request is invalid",
                List.of(),
                request);
    }

    @ExceptionHandler(TenantSlugAlreadyExistsException.class)
    ResponseEntity<ApiErrorResponse> duplicatedSlug(
            TenantSlugAlreadyExistsException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "TENANT_SLUG_ALREADY_EXISTS",
                "The tenant slug is already in use",
                List.of(),
                request);
    }

    @ExceptionHandler(TenantPersistenceConflictException.class)
    ResponseEntity<ApiErrorResponse> persistenceConflict(
            TenantPersistenceConflictException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "TENANT_DATA_CONFLICT",
                "Tenant data conflicts with an existing record",
                List.of(),
                request);
    }

    private static ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            List<ApiErrorResponse.FieldError> fieldErrors,
            HttpServletRequest request) {
        UUID correlationId = CorrelationIds.resolve(request.getHeader(TenantController.CORRELATION_HEADER));
        return ResponseEntity.status(status)
                .header(TenantController.CORRELATION_HEADER, correlationId.toString())
                .body(new ApiErrorResponse(code, message, fieldErrors, correlationId));
    }
}
