package multiroom.tts.rest;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.metrics.TtsMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;

import java.util.EnumSet;
import java.util.Set;

/**
 * The single {@link TtsErrorCode} to HTTP status mapping. Scoped to this module's own
 * controllers: under 019's shared {@code DispatcherServlet}, an unscoped {@code
 * @RestControllerAdvice} here would otherwise convert another module's exceptions into this
 * module's error shape.
 */
@RestControllerAdvice(assignableTypes = {TtsController.class, TtsCacheController.class, TtsVoiceController.class})
public class TtsExceptionHandler {

    private static final Set<TtsErrorCode> CALLER_FIXABLE = EnumSet.of(
            TtsErrorCode.INVALID_REQUEST, TtsErrorCode.TARGET_NOT_FOUND, TtsErrorCode.PROVIDER_NOT_FOUND,
            TtsErrorCode.INVALID_VOICE);

    private final TtsMetrics metrics;

    public TtsExceptionHandler(TtsMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Not counted here: {@code TtsService} already counted every error it throws, and a second
     * count would double the rejection rate.
     */
    @ExceptionHandler(TtsException.class)
    public ResponseEntity<ErrorResponse> handleTtsException(TtsException e) {
        HttpStatus status = CALLER_FIXABLE.contains(e.getErrorCode())
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(new ErrorResponse(e.getErrorCode().name(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationFailure(MethodArgumentNotValidException e,
                                                                 HandlerMethod handlerMethod) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Invalid request");
        return invalidRequest(message, handlerMethod);
    }

    /**
     * A body that is not JSON, or names an unknown {@code targetType}, gets the contract's error
     * shape rather than Spring's default. The body is never echoed: it is caller text.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e,
                                                              HandlerMethod handlerMethod) {
        return invalidRequest("Request body could not be read", handlerMethod);
    }

    /**
     * These are rejected before {@code TtsService} runs, so they are counted here, and only for a
     * speak request: the other controllers do not make announcements. The provider is {@code
     * unknown} because the body was never read far enough to trust one.
     */
    private ResponseEntity<ErrorResponse> invalidRequest(String message, HandlerMethod handlerMethod) {
        if (handlerMethod != null && TtsController.class.isAssignableFrom(handlerMethod.getBeanType())) {
            metrics.announcementRejected(TtsMetrics.UNKNOWN, TtsErrorCode.INVALID_REQUEST.name(), TtsMetrics.NONE);
        }
        return ResponseEntity.badRequest().body(new ErrorResponse(TtsErrorCode.INVALID_REQUEST.name(), message));
    }
}
