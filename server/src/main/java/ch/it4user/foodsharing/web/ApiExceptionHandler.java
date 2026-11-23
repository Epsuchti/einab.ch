package ch.it4user.foodsharing.web;

import ch.it4user.foodsharing.openapi.model.ErrorResponse;
import ch.it4user.foodsharing.openapi.model.ErrorCode;
import ch.it4user.foodsharing.service.ApiErrorCode;
import ch.it4user.foodsharing.service.ApiException;
import ch.it4user.foodsharing.service.SwissPublicHolidayCalendar;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception thrown by the API controllers into an {@link ErrorResponse} with an
 * {@link ErrorCode}, so the client can always show a translated message.
 */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
        if (exception.getStatus().is5xxServerError()) {
            log.error("API request failed with {}", exception.getCode(), exception);
        }
        return ResponseEntity.status(exception.getStatus())
                .body(error(exception.getStatus(), exception.getCode(), exception.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(error(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        List<String> details = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(error(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, details));
    }

    /** Malformed JSON, missing or mistyped parameters, or parameters failing method validation. */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception exception) {
        log.debug("Rejected bad request: {}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(error(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(error(HttpStatus.METHOD_NOT_ALLOWED, ApiErrorCode.VALIDATION_FAILED, List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiErrorCode.VALIDATION_FAILED, List.of()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(error(HttpStatus.CONTENT_TOO_LARGE, ApiErrorCode.PAYLOAD_TOO_LARGE, List.of()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error(HttpStatus.FORBIDDEN, ApiErrorCode.ACCESS_DENIED, List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, List.of()));
    }

    /** Someone else changed or removed the same data at the same time. */
    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ErrorResponse> handleConflict(Exception exception) {
        log.warn("Conflicting data change: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT, List.of()));
    }

    /** Foodsharing (or another outside service) did not answer or failed. */
    @ExceptionHandler({
            ResourceAccessException.class,
            HttpServerErrorException.class,
            SwissPublicHolidayCalendar.PublicHolidayCalendarUnavailableException.class
    })
    public ResponseEntity<ErrorResponse> handleExternalServiceUnavailable(Exception exception) {
        log.warn("External service unavailable: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(error(HttpStatus.BAD_GATEWAY, ApiErrorCode.EXTERNAL_SERVICE_UNAVAILABLE, List.of()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        HttpStatusCode status = exception.getStatusCode();
        ApiErrorCode code = switch (status.value()) {
            case 400 -> ApiErrorCode.VALIDATION_FAILED;
            case 401 -> ApiErrorCode.AUTHENTICATION_REQUIRED;
            case 403 -> ApiErrorCode.ACCESS_DENIED;
            case 404 -> ApiErrorCode.RESOURCE_NOT_FOUND;
            case 409 -> ApiErrorCode.CONFLICT;
            default -> ApiErrorCode.UNEXPECTED_ERROR;
        };
        if (status.is5xxServerError()) {
            log.error("API request failed with status {}", status.value(), exception);
        }
        return ResponseEntity.status(status).body(error(status, code, List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception exception) {
        log.error("Unexpected error in API request", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.UNEXPECTED_ERROR, List.of()));
    }

    private ErrorResponse error(HttpStatusCode status, ApiErrorCode code, List<String> details) {
        ErrorResponse response = new ErrorResponse();
        response.setStatus(status.value());
        response.setMessage(code.name());
        response.setCode(toApiCode(code));
        response.setDetails(details);
        return response;
    }

    /** Never fail while building the error itself: an unknown code still reaches the client as UNEXPECTED_ERROR. */
    private ErrorCode toApiCode(ApiErrorCode code) {
        try {
            return ErrorCode.fromValue(code.name());
        } catch (IllegalArgumentException exception) {
            log.error("ApiErrorCode {} is missing from the ErrorCode enum in openapi.yaml", code);
            return ErrorCode.UNEXPECTED_ERROR;
        }
    }
}
