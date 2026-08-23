package com.petcare.enterprise.tenancy.presentation;

import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorCode;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponse;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponseFactory;
import com.petcare.enterprise.tenancy.application.TenantAccessDeniedException;
import com.petcare.enterprise.tenancy.application.TenantAuthenticationRequiredException;
import com.petcare.enterprise.tenancy.application.TenantPersistenceConflictException;
import com.petcare.enterprise.tenancy.application.TenantSlugAlreadyExistsException;
import com.petcare.enterprise.tenancy.domain.DomainValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class TenantExceptionHandler {

    private final ApiErrorResponseFactory errorFactory;

    TenantExceptionHandler(ApiErrorResponseFactory errorFactory) {
        this.errorFactory = errorFactory;
    }

    @ExceptionHandler(DomainValidationException.class)
    ResponseEntity<ApiErrorResponse> malformedDomainValue(DomainValidationException exception) {
        return errorFactory.response(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.MALFORMED_REQUEST,
                "La solicitud contiene un valor no válido.");
    }

    @ExceptionHandler(TenantSlugAlreadyExistsException.class)
    ResponseEntity<ApiErrorResponse> duplicatedSlug(
            TenantSlugAlreadyExistsException exception) {
        return errorFactory.response(
                HttpStatus.CONFLICT,
                ApiErrorCode.TENANT_SLUG_ALREADY_EXISTS,
                "El slug de la clínica ya está en uso.");
    }

    @ExceptionHandler(TenantPersistenceConflictException.class)
    ResponseEntity<ApiErrorResponse> persistenceConflict(
            TenantPersistenceConflictException exception) {
        return errorFactory.response(
                HttpStatus.CONFLICT,
                ApiErrorCode.TENANT_DATA_CONFLICT,
                "Los datos de la clínica entran en conflicto con un registro existente.");
    }

    @ExceptionHandler(TenantAuthenticationRequiredException.class)
    ResponseEntity<ApiErrorResponse> unauthenticated(TenantAuthenticationRequiredException exception) {
        return errorFactory.response(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.UNAUTHORIZED,
                "Debes autenticarte para continuar.");
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> invalidTenantIdentity(TenantAccessDeniedException exception) {
        return errorFactory.response(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.ACCESS_DENIED,
                "No tienes permiso para realizar esta operación.");
    }
}
