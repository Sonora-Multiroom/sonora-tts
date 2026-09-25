package multiroom.tts;

/**
 * The closed set of error codes the REST contract publishes, one constant per value of the
 * {@code ErrorResponse.error} enum in
 * {@code specs/002-google-voice-selection/contracts/tts-rest-api.yaml} (v0.1.1). From 0.1.1 on
 * that contract's version is the JAR's version; it supersedes 001's independently numbered
 * v0.3.0. Nothing below {@code rest} may invent a code outside this set.
 */
public enum TtsErrorCode {
    INVALID_REQUEST,
    TARGET_NOT_FOUND,
    PROVIDER_NOT_FOUND,
    PROVIDER_TIMEOUT,
    PROVIDER_RATE_LIMITED,
    PROVIDER_ERROR,
    FORMAT_NORMALIZATION_FAILED,
    /** The resolved voice is not in the provider's loaded voice catalogue (400). */
    INVALID_VOICE,
    /** Voice listing while the catalogue cannot be fetched, or a recent failure is in back-off (503). */
    VOICE_CATALOGUE_UNAVAILABLE
}
