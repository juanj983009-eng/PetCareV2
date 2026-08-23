package com.petcare.enterprise.tenancy.presentation;

import com.petcare.enterprise.audit.application.CorrelationIdProvider;
import com.petcare.enterprise.tenancy.application.TenantAccessDeniedException;
import com.petcare.enterprise.tenancy.application.TenantAuthenticationRequiredException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class TenantContextExceptionHandler {

    private final CorrelationIdProvider correlationIdProvider;

    TenantContextExceptionHandler(CorrelationIdProvider correlationIdProvider) {
        this.correlationIdProvider = correlationIdProvider;
    }

    @ExceptionHandler(TenantAuthenticationRequiredException.class)
    ResponseEntity<ApiErrorResponse> unauthenticated(TenantAuthenticationRequiredException exception) {
        return response(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> invalidTenantIdentity(TenantAccessDeniedException exception) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "The authenticated identity cannot access this resource");
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message) {
        UUID correlationId = correlationIdProvider.currentCorrelationId();
        return ResponseEntity.status(status)
                .header(TenantController.CORRELATION_HEADER, correlationId.toString())
                .body(new ApiErrorResponse(code, message, List.of(), correlationId));
    }
}
