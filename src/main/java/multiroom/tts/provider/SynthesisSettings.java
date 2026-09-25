package multiroom.tts.provider;

/**
 * What {@link TtsProvider#resolveSettings} decided: the effective voice, language, engine and
 * audio adjustments for one announcement, plus the forms of them the cache key is built from.
 *
 * <p>The {@code *Key} fields exist so that {@code TtsService} can build a cache key without
 * knowing any provider's rules (Principle IV): the provider decides which spellings are the same
 * voice and which values are neutral, and the service copies the keys verbatim.
 *
 * @param voice           the voice to synthesize with; {@code null} lets the provider choose
 * @param voiceKey        the cache-key form of {@code voice}: equal for any two spellings that
 *                        produce the same audio (for {@code google-cloud}, the full name
 *                        case-folded; otherwise {@code voice} itself)
 * @param requestedVoice  the voice exactly as the caller or the configuration wrote it, before
 *                        resolution. Used only to quote the caller's own spelling in error
 *                        messages, and <strong>never</strong> part of the cache key — otherwise
 *                        {@code charn} and {@code Charn} would split entries
 * @param language        the effective language tag
 * @param engine          the effective engine, or {@code null} when {@code voice} already encodes it
 * @param pitch           pitch in semitones to send, or {@code null} to send none
 * @param speakingRate    speaking rate to send, or {@code null} to send none
 * @param pitchKey        the cache-key form of {@code pitch}: {@code null} when the provider
 *                        considers the value neutral, so an explicit default shares the entry of
 *                        no value
 * @param speakingRateKey the cache-key form of {@code speakingRate}, on the same terms
 */
public record SynthesisSettings(String voice, String voiceKey, String requestedVoice, String language,
                                String engine, Double pitch, Double speakingRate, Double pitchKey,
                                Double speakingRateKey) {

    /**
     * Settings with no audio adjustments, whose voice is its own cache key and its own requested
     * spelling — the 001 shape every non-Google provider resolves to.
     */
    public static SynthesisSettings of(String voice, String language, String engine) {
        return new SynthesisSettings(voice, voice, voice, language, engine, null, null, null, null);
    }
}
