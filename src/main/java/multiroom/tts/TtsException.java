package multiroom.tts;

/**
 * The one exception type services and providers throw. Nothing below the {@code rest} package
 * knows about HTTP status codes — {@code TtsExceptionHandler} holds the single
 * {@link TtsErrorCode} to status mapping.
 */
public class TtsException extends RuntimeException {

    private final TtsErrorCode errorCode;

    public TtsException(TtsErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TtsException(TtsErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public TtsErrorCode getErrorCode() {
        return errorCode;
    }
}
