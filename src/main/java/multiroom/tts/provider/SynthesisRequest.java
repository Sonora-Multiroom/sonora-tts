package multiroom.tts.provider;

/**
 * What a {@link TtsProvider} is asked to synthesize.
 *
 * <p>{@code voice} and {@code language} carry the <strong>effective</strong> values: the
 * request's override where given, otherwise the chosen provider's configured default.
 *
 * @param text             validated, trimmed text to speak
 * @param voice            resolved voice identifier
 * @param language         resolved BCP 47 language tag
 * @param targetSampleRate the system-native sample rate the caller ultimately wants
 * @param targetChannels   the system-native channel count the caller ultimately wants
 */
public record SynthesisRequest(String text, String voice, String language, int targetSampleRate,
                                int targetChannels) {
}
