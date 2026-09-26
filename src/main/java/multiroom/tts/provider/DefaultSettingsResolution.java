package multiroom.tts.provider;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.TtsProviderConfig;

/**
 * 001's resolution rule, which OpenAI, Piper and local HTTP keep exactly:
 * the request's voice and language win over the configured ones, the language falls back to
 * {@code en-US}, and the engine is the configured one. The resolved values — and therefore the
 * cache keys — are identical to 001's.
 */
public final class DefaultSettingsResolution {

    /**
     * The language 001 declared as {@code TtsProviderConfig}'s field default. It lives here now so
     * that {@code google-cloud}, which must not have it, can do without it.
     */
    private static final String DEFAULT_LANGUAGE = "en-US";

    private DefaultSettingsResolution() {
    }

    /**
     * @throws TtsException {@link TtsErrorCode#INVALID_REQUEST} if the request overrides
     *                      {@code engine}, {@code pitch} or {@code speakingRate}, which only
     *                      {@code google-cloud} supports, or sets a {@code stylePrompt}, which only
     *                      {@code google-gemini} does — even an empty one, since it asks for prompt
     *                      behaviour this type does not have
     */
    public static SynthesisSettings resolve(TtsProviderConfig config, RequestedSettings requested) {
        rejectIfSet(config, "engine", requested.engine());
        rejectIfSet(config, "pitch", requested.pitch());
        rejectIfSet(config, "speakingRate", requested.speakingRate());
        rejectIfSet(config, "stylePrompt", requested.stylePrompt());

        String voice = requested.voice() != null ? requested.voice() : config.getVoice();
        String language = requested.language() != null ? requested.language()
                : config.getLanguage() != null ? config.getLanguage()
                : DEFAULT_LANGUAGE;
        return SynthesisSettings.of(voice, language, config.getEngine());
    }

    private static void rejectIfSet(TtsProviderConfig config, String field, Object value) {
        if (value != null) {
            throw new TtsException(TtsErrorCode.INVALID_REQUEST, "Field '" + field
                    + "' is not supported by provider '" + config.getName() + "' of type " + config.getType());
        }
    }
}
