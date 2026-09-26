package multiroom.tts.provider.cloud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.config.VoiceCatalogueProperties;
import multiroom.tts.provider.RequestedSettings;
import multiroom.tts.provider.SynthesisRequest;
import multiroom.tts.provider.SynthesisResult;
import multiroom.tts.provider.SynthesisSettings;
import multiroom.tts.provider.TtsProvider;
import multiroom.tts.provider.VoiceCatalogueProvider;
import multiroom.tts.provider.cloud.google.CatalogueVoice;
import multiroom.tts.provider.cloud.google.GeminiSettingsResolver;
import multiroom.tts.provider.cloud.google.GoogleTtsClient;
import multiroom.tts.provider.cloud.google.GoogleVoiceCatalogue;
import multiroom.tts.provider.cloud.google.ServiceAccountKey;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google's Text-to-Speech {@code v1} {@code text:synthesize}, route A (004): a service account
 * only, never an API key, carrying the entry's Gemini model as {@code voice.modelName}.
 *
 * <p>It lists the Gemini voices from Google's published voice list, but never checks an
 * announcement's voice against it: the list names no model, so it could reject a voice a newer
 * model offers or accept one a model lacks. An unknown model or voice is Google's own refusal,
 * reported to the caller. The shared send, 401 retry, error mapping and voice-list fetch are
 * {@link GoogleTtsClient}'s.
 */
public class GoogleGeminiTtsProvider implements TtsProvider, VoiceCatalogueProvider {

    private static final URI DEFAULT_API_BASE = URI.create("https://texttospeech.googleapis.com/v1/");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final GeminiSettingsResolver resolver;
    private final GoogleTtsClient client;
    private final GoogleVoiceCatalogue catalogue;
    private final Duration timeout;
    private final Duration fetchTimeout;
    private final Clock clock;

    public GoogleGeminiTtsProvider(TtsProviderConfig config, VoiceCatalogueProperties catalogueProperties,
                                   ServiceAccountKey serviceAccountKey, int maxTextLength) {
        this(config, catalogueProperties, serviceAccountKey, maxTextLength, DEFAULT_API_BASE, Clock.systemUTC());
    }

    /** Visible so tests can point this provider at a WireMock server instead of the real API. */
    public GoogleGeminiTtsProvider(TtsProviderConfig config, VoiceCatalogueProperties catalogueProperties,
                                   ServiceAccountKey serviceAccountKey, int maxTextLength, URI apiBase, Clock clock) {
        this.name = config.getName();
        this.resolver = new GeminiSettingsResolver(config, maxTextLength);
        this.timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        this.client = new GoogleTtsClient(name, timeout, apiBase, serviceAccountKey, null,
                catalogueProperties.getFailureBackoff(), clock);
        this.fetchTimeout = catalogueProperties.getFetchTimeout();
        this.clock = clock;
        // One voice list per entry, never shared; constructing it fetches nothing.
        this.catalogue = new GoogleVoiceCatalogue(name, catalogueProperties, clock, client::fetchVoices,
                GoogleVoiceCatalogue.geminiSelector(config.getModel()));
    }

    @Override
    public SynthesisSettings resolveSettings(RequestedSettings requested) {
        return resolver.resolve(requested);
    }

    /**
     * Every Gemini voice Google publishes, sorted by name, with this entry's model as the engine.
     * {@code language} does not narrow the list: {@code VoiceQueryService} has already checked its
     * form, and a Gemini voice is not tied to a language (Google tags each one {@code en-US} only).
     * Like {@code google-cloud}'s listing, it gets only {@code fetch-timeout}.
     *
     * @throws TtsException {@link TtsErrorCode#INVALID_REQUEST} for an {@code engine} filter,
     *                      because the model is fixed by the entry;
     *                      {@link TtsErrorCode#VOICE_CATALOGUE_UNAVAILABLE} when the list cannot be
     *                      fetched now
     */
    @Override
    public List<CatalogueVoice> listVoices(String language, String engine) {
        if (engine != null) {
            throw new TtsException(TtsErrorCode.INVALID_REQUEST, "Filter 'engine' is not supported by provider '"
                    + name + "' of type " + ProviderType.GOOGLE_GEMINI);
        }
        return catalogue.list(null, null, fetchTimeout);
    }

    /**
     * @return audio whose reported sample rate is the one requested, but Google may answer at
     *         another (24 kHz for Gemini) — the WAV header is authoritative, because {@code
     *         AudioConverter} reads the source format from it, not from this value
     */
    @Override
    public SynthesisResult synthesize(SynthesisRequest request) {
        Instant deadline = clock.instant().plus(timeout);
        client.prepare(remaining(deadline));
        byte[] audio = client.synthesize(requestBody(request), deadline);
        return new SynthesisResult(audio, request.targetSampleRate(), 1, 16);
    }

    private static String requestBody(SynthesisRequest request) {
        SynthesisSettings settings = request.settings();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", request.text());
        if (settings.stylePrompt() != null) {
            input.put("prompt", settings.stylePrompt());
        }
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("languageCode", request.language());
        voice.put("name", request.voice());
        voice.put("modelName", settings.engine());

        Map<String, Object> audioConfig = new LinkedHashMap<>();
        audioConfig.put("audioEncoding", "LINEAR16");
        audioConfig.put("sampleRateHertz", request.targetSampleRate());
        if (settings.speakingRate() != null) {
            audioConfig.put("speakingRate", settings.speakingRate());
        }

        Map<String, Object> body = Map.of("input", input, "voice", voice, "audioConfig", audioConfig);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Gemini TTS request", e);
        }
    }

    private Duration remaining(Instant deadline) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
