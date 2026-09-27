package com.petcare.enterprise.appointments.presentation;

import com.petcare.enterprise.appointments.application.InvalidAppointmentScheduleQueryException;
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
class AppointmentExceptionHandler {

    private final ApiErrorResponseFactory errors;

    AppointmentExceptionHandler(ApiErrorResponseFactory errors) {
        this.errors = Objects.requireNonNull(errors, "errors is required");
    }

    @ExceptionHandler(InvalidAppointmentScheduleQueryException.class)
    ResponseEntity<ApiErrorResponse> invalidQuery(InvalidAppointmentScheduleQueryException exception) {
        return errors.response(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_FAILED,
                exception.getMessage(),
                List.of(new ApiFieldError(exception.field(), "INVALID_VALUE", exception.getMessage())));
    }
}
