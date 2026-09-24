package multiroom.tts;

/**
 * The closed set of error codes {@code contracts/tts-rest-api.yaml} publishes. One constant per
 * value in that YAML's {@code ErrorResponse.error} enum — nothing below {@code rest} may invent a
 * code outside this set (FR-032).
 */
public enum TtsErrorCode {
    INVALID_REQUEST,
    TARGET_NOT_FOUND,
    PROVIDER_NOT_FOUND,
    PROVIDER_TIMEOUT,
    PROVIDER_RATE_LIMITED,
    PROVIDER_ERROR,
    FORMAT_NORMALIZATION_FAILED
}
