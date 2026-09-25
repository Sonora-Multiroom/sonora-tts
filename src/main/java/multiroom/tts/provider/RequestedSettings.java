package multiroom.tts.provider;

/**
 * Per-request overrides handed to {@link TtsProvider#resolveSettings}. Every field is nullable:
 * {@code null} means "use the provider entry's configured value".
 *
 * @param voice        voice override, as the caller wrote it
 * @param language     language tag override
 * @param engine       engine override ({@code google-cloud} only)
 * @param pitch        pitch override in semitones ({@code google-cloud} only)
 * @param speakingRate speaking-rate override ({@code google-cloud} only)
 */
public record RequestedSettings(String voice, String language, String engine, Double pitch,
                                Double speakingRate) {
}
