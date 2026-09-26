package multiroom.tts.provider.cloud.google;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.RequestedSettings;
import multiroom.tts.provider.SynthesisSettings;

import java.util.Locale;

/**
 * Turns a request's overrides and one {@code google-cloud} entry's configuration into canonical
 * {@link SynthesisSettings} — data-model.md "Resolution algorithm". Pure: no I/O, so it can run on
 * every request before the cache lookup. Whether the resolved voice exists is
 * checked later, on a cache miss only, against the voice catalogue.
 */
public final class GoogleVoiceResolver {

    /** Pitch range in semitones, inclusive, from Google's v1 AudioConfig. */
    public static final double MIN_PITCH = -20.0;
    public static final double MAX_PITCH = 20.0;

    /**
     * Google's own default. An explicit default sounds exactly like no value, so it is left out
     * of the cache key and shares the entry of no value — while still being
     * sent, because it was set. Speaking rate's own neutral value lives in {@link GoogleSpeakingRate}.
     */
    private static final double NEUTRAL_PITCH = 0.0;

    /** Used only when no voice is set anywhere, so there is no voice to take a language from. */
    private static final GoogleLanguage FALLBACK_LANGUAGE = GoogleLanguage.parse("en-US");

    private final TtsProviderConfig config;

    /** The configured voice, parsed; {@code null} when the entry sets none. */
    private final GoogleVoiceName configuredVoice;

    /**
     * The entry's default engine for short names: its {@code engine} setting, or else
     * the engine of its configured full voice. {@code null} when neither is recognized.
     */
    private final GoogleEngine defaultEngine;

    /**
     * The configured full voice's engine segment when it is not a recognized engine
     * ({@code Polyglot}); kept only to explain why short names cannot be composed.
     */
    private final String unrecognizedDefaultEngine;

    /**
     * The entry's default language for short names: its {@code language} setting, or
     * else the prefix of its configured full voice. {@code null} when neither exists.
     */
    private final GoogleLanguage defaultLanguage;

    /**
     * @param config the entry, already format-checked at start-up by {@code TtsProperties}
     */
    public GoogleVoiceResolver(TtsProviderConfig config) {
        this.config = config;
        this.configuredVoice = config.getVoice() == null ? null : GoogleVoiceName.parse(config.getVoice());
        GoogleVoiceName.Full configuredFull = configuredVoice instanceof GoogleVoiceName.Full full ? full : null;

        GoogleEngine engine = GoogleEngine.fromName(config.getEngine()).orElse(null);
        String unrecognized = null;
        if (engine == null && configuredFull != null) {
            engine = configuredFull.engine().orElse(null);
            unrecognized = engine == null ? configuredFull.engineSegment() : null;
        }
        this.defaultEngine = engine;
        this.unrecognizedDefaultEngine = unrecognized;

        this.defaultLanguage = config.getLanguage() != null ? GoogleLanguage.parse(config.getLanguage())
                : configuredFull != null ? configuredFull.language()
                : null;
    }

    /**
     * @throws TtsException {@link TtsErrorCode#INVALID_REQUEST} for a malformed override, one that
     *                      contradicts the voice, a short voice that cannot be completed, or a
     *                      {@code stylePrompt} — even an empty one, since it asks for prompt
     *                      behaviour {@code google-cloud} does not have
     */
    public SynthesisSettings resolve(RequestedSettings requested) {
        if (requested.stylePrompt() != null) {
            throw invalid("Field 'stylePrompt' is not supported by provider '" + config.getName() + "' of type "
                    + config.getType());
        }
        GoogleEngine requestedEngine = parseRequestedEngine(requested.engine());
        GoogleLanguage requestedLanguage = parseRequestedLanguage(requested.language());
        checkRange("pitch", requested.pitch(), MIN_PITCH, MAX_PITCH);
        GoogleSpeakingRate.check("speakingRate", requested.speakingRate());
        Double pitch = requested.pitch() != null ? requested.pitch() : config.getPitch();
        Double speakingRate = requested.speakingRate() != null ? requested.speakingRate() : config.getSpeakingRate();

        String written = requested.voice() != null ? requested.voice() : config.getVoice();
        if (written == null) {
            if (requestedEngine != null) {
                throw invalid("Engine '" + requestedEngine.canonical() + "' needs a voice, but neither the "
                        + "request nor provider '" + config.getName() + "' names one");
            }
            GoogleLanguage language = requestedLanguage != null ? requestedLanguage
                    : defaultLanguage != null ? defaultLanguage
                    : FALLBACK_LANGUAGE;
            return settings(null, null, language, pitch, speakingRate);
        }

        GoogleVoiceName name = requested.voice() != null ? parseRequestedVoice(written) : configuredVoice;
        GoogleVoiceName.Full full = name instanceof GoogleVoiceName.Full given
                ? checkFullName(given, requestedEngine, requestedLanguage)
                : compose((GoogleVoiceName.Short) name, requestedEngine, requestedLanguage);
        return settings(full.canonical(), written, full.language(), pitch, speakingRate);
    }

    /** A full name is used as given; the entry's engine does not apply to it. */
    private static GoogleVoiceName.Full checkFullName(GoogleVoiceName.Full name, GoogleEngine requestedEngine,
                                                      GoogleLanguage requestedLanguage) {
        if (requestedLanguage != null && !requestedLanguage.equals(name.language())) {
            throw invalid("Language '" + requestedLanguage + "' contradicts voice '" + name
                    + "', whose language is '" + name.language() + "'");
        }
        if (requestedEngine != null && name.engine().filter(requestedEngine::equals).isEmpty()) {
            throw invalid("Engine '" + requestedEngine.canonical() + "' contradicts voice '" + name
                    + "', whose engine is '" + name.engineSpelling() + "'");
        }
        return name;
    }

    /** Completes a short name from the resolved engine and language; other engines are never searched. */
    private GoogleVoiceName.Full compose(GoogleVoiceName.Short name, GoogleEngine requestedEngine,
                                         GoogleLanguage requestedLanguage) {
        GoogleEngine engine = requestedEngine != null ? requestedEngine : defaultEngine;
        if (engine == null) {
            throw invalid(unrecognizedDefaultEngine != null
                    ? "Provider '" + config.getName() + "' has no engine that short voice names can use: its "
                            + "voice's engine '" + unrecognizedDefaultEngine + "' is not one of "
                            + GoogleEngine.supportedList() + ". Name an engine in the request, or use a full voice name"
                    : "Provider '" + config.getName() + "' has no default engine for short voice names. "
                            + "Name an engine in the request, or use a full voice name");
        }
        GoogleLanguage language = requestedLanguage != null ? requestedLanguage : defaultLanguage;
        if (language == null) {
            throw invalid("A language is needed for short voice '" + name.voice() + "', but neither the "
                    + "request nor provider '" + config.getName() + "' sets one");
        }
        return name.compose(engine, language);
    }

    private static SynthesisSettings settings(String voice, String requestedVoice, GoogleLanguage language,
                                              Double pitch, Double speakingRate) {
        // The key is case-folded: the catalogue and the rule can spell one voice differently,
        // and Google voice names are unique regardless of case.
        String voiceKey = voice == null ? null : voice.toLowerCase(Locale.ROOT);
        return new SynthesisSettings(voice, voiceKey, requestedVoice, language.tag(), null,
                pitch, speakingRate, keyOf(pitch, NEUTRAL_PITCH), GoogleSpeakingRate.keyOf(speakingRate));
    }

    private static Double keyOf(Double value, double neutral) {
        return value == null || value == neutral ? null : value;
    }

    private static GoogleEngine parseRequestedEngine(String engine) {
        if (engine == null) {
            return null;
        }
        return GoogleEngine.fromName(engine).orElseThrow(() -> invalid(
                "Unknown engine '" + engine + "'. Supported: " + GoogleEngine.supportedList()));
    }

    private static GoogleLanguage parseRequestedLanguage(String language) {
        if (language == null) {
            return null;
        }
        if (!GoogleLanguage.isWellFormed(language)) {
            throw invalid("Language '" + language + "' is not a language-region tag such as uk-UA, cmn-CN or es-419");
        }
        return GoogleLanguage.parse(language);
    }

    private static GoogleVoiceName parseRequestedVoice(String voice) {
        if (!GoogleVoiceName.isWellFormed(voice)) {
            throw invalid("Voice '" + voice + "' is neither a full voice name such as uk-UA-Chirp3-HD-Charon "
                    + "nor a short voice name such as Charon");
        }
        return GoogleVoiceName.parse(voice);
    }

    private static void checkRange(String field, Double value, double min, double max) {
        // Both comparisons are false for NaN, so it has to be rejected explicitly.
        if (value != null && (!Double.isFinite(value) || value < min || value > max)) {
            throw invalid(field + " " + value + " is outside the allowed range [" + min + ", " + max + "]");
        }
    }

    private static TtsException invalid(String message) {
        return new TtsException(TtsErrorCode.INVALID_REQUEST, message);
    }
}
