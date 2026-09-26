package multiroom.tts.provider.cloud.google;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;

/**
 * Speaking-rate range, NaN rejection and neutral-value cache-key rule, shared by
 * {@link GoogleVoiceResolver} ({@code google-cloud}) and {@code GeminiSettingsResolver}
 * ({@code google-gemini}) so the two never drift apart.
 */
public final class GoogleSpeakingRate {

    /** Speaking-rate range, inclusive, from Google's v1 AudioConfig. */
    public static final double MIN = 0.25;
    public static final double MAX = 2.0;

    /** Google's own default: sent because it was set, but left out of the cache key. */
    public static final double NEUTRAL = 1.0;

    private GoogleSpeakingRate() {
    }

    /**
     * @throws TtsException {@link TtsErrorCode#INVALID_REQUEST} if {@code value} is non-null and
     *                      outside {@code [MIN, MAX]} or not finite
     */
    public static void check(String field, Double value) {
        if (!isInRange(value)) {
            throw new TtsException(TtsErrorCode.INVALID_REQUEST,
                    field + " " + value + " is outside the allowed range [" + MIN + ", " + MAX + "]");
        }
    }

    /** For start-up validation, which reports its own fault message rather than throwing this one. */
    public static boolean isInRange(Double value) {
        // Both comparisons are false for NaN, so it has to be rejected explicitly.
        return value == null || (Double.isFinite(value) && value >= MIN && value <= MAX);
    }

    /** {@code null} when {@code value} is unset or exactly neutral, so it shares that cache identity. */
    public static Double keyOf(Double value) {
        return value == null || value == NEUTRAL ? null : value;
    }
}
