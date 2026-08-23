package com.petcare.enterprise.shared.infrastructure.web.error;

import com.petcare.enterprise.audit.application.CorrelationIdProvider;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@Component
public final class ApiErrorResponseFactory {

    private static final Comparator<ApiFieldError> FIELD_ERROR_ORDER = Comparator
            .comparing(ApiFieldError::field)
            .thenComparing(ApiFieldError::code)
            .thenComparing(ApiFieldError::message);

    private final CorrelationIdProvider correlationIdProvider;

    public ApiErrorResponseFactory(CorrelationIdProvider correlationIdProvider) {
        this.correlationIdProvider = correlationIdProvider;
    }

    public ApiErrorResponse create(ApiErrorCode code, String message) {
        return create(code, message, List.of());
    }

    public ApiErrorResponse create(ApiErrorCode code, String message, List<ApiFieldError> fieldErrors) {
        List<ApiFieldError> orderedErrors = fieldErrors.stream()
                .sorted(FIELD_ERROR_ORDER)
                .toList();
        return new ApiErrorResponse(
                code.name(),
                message,
                orderedErrors,
                correlationIdProvider.currentCorrelationId());
    }

    public ResponseEntity<ApiErrorResponse> response(HttpStatus status, ApiErrorCode code, String message) {
        return response(status, code, message, List.of());
    }

    public ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            ApiErrorCode code,
            String message,
            List<ApiFieldError> fieldErrors) {
        ApiErrorResponse error = create(code, message, fieldErrors);
        return ResponseEntity.status(status).body(error);
    }
}
