package multiroom.tts.provider.cloud.google;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.RequestedSettings;
import multiroom.tts.provider.SynthesisSettings;

/**
 * Turns a request's overrides and one {@code google-gemini} entry's configuration into canonical
 * {@link SynthesisSettings} — data-model.md "Resolution algorithm". Pure: no I/O, so it can run on
 * every request before the cache lookup. Unlike {@link GoogleVoiceResolver}, there is no voice
 * catalogue to check later: an unknown voice or model is Google's own refusal, reported to the
 * caller when it is actually sent.
 */
public final class GeminiSettingsResolver {

    private final TtsProviderConfig config;
    private final GeminiVoice configuredVoice;
    private final GoogleLanguage configuredLanguage;
    private final GeminiModel model;
    private final int maxTextLength;

    /** The entry's default prompt, stripped once; {@code null} when there is none (blank counts as none). */
    private final String defaultStylePrompt;

    /**
     * @param config the entry, already format-checked at start-up by {@code TtsProperties}
     */
    public GeminiSettingsResolver(TtsProviderConfig config, int maxTextLength) {
        this.config = config;
        this.configuredVoice = GeminiVoice.parse(config.getVoice());
        this.configuredLanguage = GoogleLanguage.parse(config.getLanguage());
        this.model = GeminiModel.parse(config.getModel());
        this.maxTextLength = maxTextLength;
        String stripped = config.getStylePrompt() == null ? null : config.getStylePrompt().strip();
        this.defaultStylePrompt = stripped == null || stripped.isEmpty() ? null : stripped;
    }

    /**
     * @throws TtsException {@link TtsErrorCode#INVALID_REQUEST} for a malformed override or a rate
     *                      outside the allowed range
     */
    public SynthesisSettings resolve(RequestedSettings requested) {
        rejectIfSet("engine", requested.engine());
        rejectIfSet("pitch", requested.pitch());
        GeminiVoice voice = requested.voice() != null ? parseRequestedVoice(requested.voice()) : configuredVoice;
        String requestedVoice = requested.voice() != null ? requested.voice() : config.getVoice();
        GoogleLanguage language = requested.language() != null ? parseRequestedLanguage(requested.language())
                : configuredLanguage;
        GoogleSpeakingRate.check("speakingRate", requested.speakingRate());
        Double speakingRate = requested.speakingRate() != null ? requested.speakingRate() : config.getSpeakingRate();
        String stylePrompt = effectiveStylePrompt(requested.stylePrompt());

        return new SynthesisSettings(voice.name(), voice.name(), requestedVoice, language.tag(), model.name(),
                null, speakingRate, null, GoogleSpeakingRate.keyOf(speakingRate), stylePrompt);
    }

    /**
     * data-model.md "Resolution algorithm" step 5: the request's stripped prompt when it is
     * non-empty; {@code null} when the request gave a prompt that is empty after stripping;
     * otherwise the entry's stripped default, if it has one.
     */
    private String effectiveStylePrompt(String requestedPrompt) {
        if (requestedPrompt == null) {
            return defaultStylePrompt;
        }
        String stripped = requestedPrompt.strip();
        if (stripped.length() > maxTextLength) {
            throw invalid("stylePrompt length " + stripped.length() + " exceeds maximum allowed length of "
                    + maxTextLength);
        }
        return stripped.isEmpty() ? null : stripped;
    }

    private static GeminiVoice parseRequestedVoice(String voice) {
        if (!GeminiVoice.isWellFormed(voice)) {
            throw invalid("Voice '" + voice + "' is not a Gemini voice name: one word of letters, such as Kore");
        }
        return GeminiVoice.parse(voice);
    }

    private static GoogleLanguage parseRequestedLanguage(String language) {
        if (!GoogleLanguage.isWellFormed(language)) {
            throw invalid("Language '" + language + "' is not a language-region tag such as uk-UA, cmn-CN or es-419");
        }
        return GoogleLanguage.parse(language);
    }

    private void rejectIfSet(String field, Object value) {
        if (value != null) {
            throw invalid("Field '" + field + "' is not supported by provider '" + config.getName() + "' of type "
                    + config.getType());
        }
    }

    private static TtsException invalid(String message) {
        return new TtsException(TtsErrorCode.INVALID_REQUEST, message);
    }
}
