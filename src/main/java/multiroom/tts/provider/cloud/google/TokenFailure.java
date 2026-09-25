package multiroom.tts.provider.cloud.google;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;

/**
 * A failed token exchange, classified by whether it says anything about the key. It reuses the
 * published error codes: the message, not the code, carries Google's diagnosis.
 */
public abstract sealed class TokenFailure extends TtsException permits TokenFailure.Unavailable, TokenFailure.Rejected {

    private final String reason;

    private TokenFailure(TtsErrorCode code, String providerName, String reason, Throwable cause) {
        super(code, message(providerName, reason), cause);
        this.reason = reason;
    }

    /** {@code Provider '<name>' could not obtain an access token: <reason>}. */
    public static String message(String providerName, String reason) {
        return "Provider '" + providerName + "' could not obtain an access token: " + reason;
    }

    /** What went wrong, without the entry prefix. */
    public String reason() {
        return reason;
    }

    /**
     * The token service cannot serve now: a timeout, an I/O failure, a server error or a rate
     * limit. None says anything about the key, so it is held off for {@code failure-backoff}.
     */
    public static final class Unavailable extends TokenFailure {

        public Unavailable(TtsErrorCode code, String providerName, String reason, Throwable cause) {
            super(code, providerName, reason, cause);
        }
    }

    /**
     * Google's verdict on the key or the request, or an answer that cannot be used. The operator
     * may fix the cause on Google's side at any moment, so it is never held off.
     */
    public static final class Rejected extends TokenFailure {

        public Rejected(TtsErrorCode code, String providerName, String reason) {
            super(code, providerName, reason, null);
        }
    }
}
