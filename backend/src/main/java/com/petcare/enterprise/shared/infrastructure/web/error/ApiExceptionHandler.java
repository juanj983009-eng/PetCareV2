package com.petcare.enterprise.shared.infrastructure.web.error;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.context.MessageSourceResolvable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ApiErrorResponseFactory errorFactory;

    ApiExceptionHandler(ApiErrorResponseFactory errorFactory) {
        this.errorFactory = errorFactory;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> beanValidation(MethodArgumentNotValidException exception) {
        return validationResponse(fieldErrors(exception.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ApiErrorResponse> binding(BindException exception) {
        return validationResponse(fieldErrors(exception.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> invalidParameterType(MethodArgumentTypeMismatchException exception) {
        return validationResponse(List.of(ApiFieldError.of(
                safeField(exception.getName()),
                ApiFieldErrorCode.INVALID_TYPE,
                "El tipo de dato no es válido.")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiErrorResponse> missingParameter(MissingServletRequestParameterException exception) {
        return validationResponse(List.of(ApiFieldError.of(
                safeField(exception.getParameterName()),
                ApiFieldErrorCode.REQUIRED,
                "Este campo es obligatorio.")));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiErrorResponse> methodValidation(HandlerMethodValidationException exception) {
        List<ApiFieldError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(error -> ApiFieldError.of(
                        safeField(result.getMethodParameter().getParameterName()),
                        fieldCode(primaryCode(error)),
                        fieldMessage(primaryCode(error)))))
                .toList();
        return validationResponse(errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> constraintViolation(ConstraintViolationException exception) {
        List<ApiFieldError> errors = exception.getConstraintViolations().stream()
                .map(violation -> ApiFieldError.of(
                        safeField(lastPathSegment(violation.getPropertyPath().toString())),
                        ApiFieldErrorCode.INVALID_VALUE,
                        "El valor del campo no es válido."))
                .toList();
        return validationResponse(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> malformedRequest(HttpMessageNotReadableException exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.MALFORMED_REQUEST,
                "La solicitud no tiene un formato JSON válido.");
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiErrorResponse> resourceNotFound(Exception exception) {
        return response(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.RESOURCE_NOT_FOUND,
                "El recurso solicitado no está disponible.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return response(
                HttpStatus.METHOD_NOT_ALLOWED,
                ApiErrorCode.METHOD_NOT_ALLOWED,
                "El método HTTP no está permitido para este recurso.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return response(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "El tipo de contenido no es compatible con este recurso.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> persistenceConflict(DataIntegrityViolationException exception) {
        return response(
                HttpStatus.CONFLICT,
                ApiErrorCode.CONFLICT,
                "La operación entra en conflicto con datos existentes.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception exception) {
        ApiErrorResponse error = errorFactory.create(
                ApiErrorCode.INTERNAL_ERROR,
                "No fue posible completar la solicitud.");
        LOGGER.error("Unhandled API error correlationId={}", error.correlationId(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private ResponseEntity<ApiErrorResponse> validationResponse(List<ApiFieldError> fieldErrors) {
        return errorFactory.response(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_FAILED,
                "Revisa los campos indicados.",
                fieldErrors);
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            ApiErrorCode code,
            String message) {
        return errorFactory.response(status, code, message);
    }

    private static List<ApiFieldError> fieldErrors(List<FieldError> errors) {
        return errors.stream()
                .map(error -> ApiFieldError.of(
                        error.getField(),
                        fieldCode(error.getCode()),
                        fieldMessage(error.getCode())))
                .toList();
    }

    private static ApiFieldErrorCode fieldCode(String validationCode) {
        return switch (String.valueOf(validationCode)) {
            case "NotBlank", "NotNull", "NotEmpty" -> ApiFieldErrorCode.REQUIRED;
            case "Pattern" -> ApiFieldErrorCode.INVALID_FORMAT;
            case "Size" -> ApiFieldErrorCode.INVALID_SIZE;
            case "typeMismatch" -> ApiFieldErrorCode.INVALID_TYPE;
            default -> ApiFieldErrorCode.INVALID_VALUE;
        };
    }

    private static String primaryCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
    }

    private static String fieldMessage(String validationCode) {
        return switch (fieldCode(validationCode)) {
            case REQUIRED -> "Este campo es obligatorio.";
            case INVALID_FORMAT -> "El formato del campo no es válido.";
            case INVALID_SIZE -> "La longitud del campo no es válida.";
            case INVALID_TYPE -> "El tipo de dato no es válido.";
            case INVALID_VALUE -> "El valor del campo no es válido.";
        };
    }

    private static String lastPathSegment(String path) {
        int separator = path.lastIndexOf('.');
        return separator >= 0 ? path.substring(separator + 1) : path;
    }

    private static String safeField(String field) {
        return field == null || field.isBlank() ? "request" : field;
    }
}
