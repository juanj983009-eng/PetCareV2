package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.InvalidCredentialsException;
import com.petcare.enterprise.identity.application.UserManagementException;
import com.petcare.enterprise.identity.application.RoleManagementException;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorCode;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponse;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponseFactory;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiFieldError;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiFieldErrorCode;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class IdentityExceptionHandler {

    private final ApiErrorResponseFactory errorFactory;

    IdentityExceptionHandler(ApiErrorResponseFactory errorFactory) {
        this.errorFactory = errorFactory;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiErrorResponse> invalidCredentials(InvalidCredentialsException exception) {
        return errorFactory.response(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.INVALID_CREDENTIALS,
                "Las credenciales proporcionadas no son válidas.");
    }

    @ExceptionHandler(UserManagementException.class)
    ResponseEntity<ApiErrorResponse> userManagement(UserManagementException exception) {
        return switch (exception.kind()) {
            case USER_NOT_FOUND, ROLE_NOT_FOUND, BRANCH_NOT_FOUND -> errorFactory.response(
                    HttpStatus.NOT_FOUND,
                    ApiErrorCode.RESOURCE_NOT_FOUND,
                    "El recurso solicitado no está disponible.");
            case EMAIL_CONFLICT -> errorFactory.response(
                    HttpStatus.CONFLICT,
                    ApiErrorCode.USER_EMAIL_ALREADY_EXISTS,
                    "Ya existe un usuario con ese correo en la clínica.",
                    List.of(ApiFieldError.of(
                            exception.field(),
                            ApiFieldErrorCode.INVALID_VALUE,
                            "El correo ya está registrado en la clínica.")));
            case CONCURRENT_CONFLICT, BLOCKED_TRANSITION, LAST_ADMINISTRATOR -> errorFactory.response(
                    HttpStatus.CONFLICT,
                    ApiErrorCode.USER_CONFLICT,
                    "El usuario cambió o no admite la operación solicitada.");
            case INVALID_SORT -> errorFactory.response(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.VALIDATION_FAILED,
                    "Revisa los campos indicados.",
                    List.of(ApiFieldError.of(
                            exception.field(),
                            ApiFieldErrorCode.INVALID_VALUE,
                            "El ordenamiento solicitado no es válido.")));
        };
    }

    @ExceptionHandler(RoleManagementException.class)
    ResponseEntity<ApiErrorResponse> roleManagement(RoleManagementException exception) {
        return switch (exception.kind()) {
            case ROLE_NOT_FOUND, USER_NOT_FOUND -> errorFactory.response(
                    HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND,
                    "El recurso solicitado no está disponible.");
            case PERMISSION_NOT_FOUND -> errorFactory.response(
                    HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    "Revisa los campos indicados.");
            case PROTECTED_ADMINISTRATOR, CONCURRENT_CONFLICT -> errorFactory.response(
                    HttpStatus.CONFLICT, ApiErrorCode.ROLE_CONFLICT,
                    "La operación dejaría sin un administrador activo o alteraría una configuración protegida.");
        };
    }
}
