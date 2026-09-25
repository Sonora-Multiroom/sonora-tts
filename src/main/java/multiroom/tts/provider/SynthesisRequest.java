package multiroom.tts.provider;

/**
 * What a {@link TtsProvider} is asked to synthesize.
 *
 * @param text             validated, trimmed text to speak
 * @param settings         what {@link TtsProvider#resolveSettings} resolved for this request
 * @param targetSampleRate the system-native sample rate the caller ultimately wants
 * @param targetChannels   the system-native channel count the caller ultimately wants
 */
public record SynthesisRequest(String text, SynthesisSettings settings, int targetSampleRate,
                               int targetChannels) {

    /** The resolved voice; shorthand for {@code settings().voice()}. */
    public String voice() {
        return settings.voice();
    }

    /** The resolved language tag; shorthand for {@code settings().language()}. */
    public String language() {
        return settings.language();
    }
}
