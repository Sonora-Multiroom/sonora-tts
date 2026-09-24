package multiroom.tts.rest;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.EnumSet;
import java.util.Set;

/**
 * The single {@link TtsErrorCode} to HTTP status mapping (FR-032). Scoped to this module's own
 * controllers: under 019's shared {@code DispatcherServlet}, an unscoped {@code
 * @RestControllerAdvice} here would otherwise convert another module's exceptions into this
 * module's error shape.
 */
@RestControllerAdvice(assignableTypes = {TtsController.class, TtsCacheController.class})
public class TtsExceptionHandler {

    private static final Set<TtsErrorCode> CALLER_FIXABLE = EnumSet.of(
            TtsErrorCode.INVALID_REQUEST, TtsErrorCode.TARGET_NOT_FOUND, TtsErrorCode.PROVIDER_NOT_FOUND);

    @ExceptionHandler(TtsException.class)
    public ResponseEntity<ErrorResponse> handleTtsException(TtsException e) {
        HttpStatus status = CALLER_FIXABLE.contains(e.getErrorCode())
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(new ErrorResponse(e.getErrorCode().name(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationFailure(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(new ErrorResponse(TtsErrorCode.INVALID_REQUEST.name(), message));
    }
}
